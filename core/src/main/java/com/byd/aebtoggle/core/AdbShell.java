package com.byd.aebtoggle.core;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.SocketTimeoutException;
import java.nio.charset.Charset;

/**
 * One "adb shell" call over an already connected channel (refactor S4). Pure, so the protocol is
 * tested with scripted replies instead of a car.
 *
 * It does exactly what AdbHelper.executeRemoteShellWithResult() did up to v19.0 S3 (same messages,
 * same order, same status for every outcome), with these additions:
 *   - every message is read with AdbFrame.read (magic checked, payload length capped BEFORE anything
 *     is allocated; the old code allocated whatever length the peer announced),
 *   - the shell output is capped at MAX_OUTPUT bytes (ERROR beyond it),
 *   - after pairing (15 s wait for "Always allow") the read timeout goes back to HANDSHAKE_TIMEOUT_MS,
 *   - the CNXN banner (e.g. "device::ro.product.model=...;features=...") is returned for the log.
 *     It is NOT checked: no reply from this car has been seen yet, so nothing about it is enforced.
 * The overall deadline per call is the caller's job (it owns the socket; see AdbHelper).
 */
public final class AdbShell {

    /** What the car connection needs from the caller. */
    public interface Channel {
        InputStream in() throws IOException;

        OutputStream out() throws IOException;

        void setReadTimeoutMs(int ms) throws IOException;

        /** Called once when the public key is offered: the driver needs time to tap "Always allow". */
        void extendDeadlineMs(int ms);
    }

    /** The key. sign() returns null when the app is not allowed to use the key ("UNAUTHORIZED"). */
    public interface Auth {
        byte[] sign(byte[] token) throws Exception;

        byte[] publicKeyPayload();
    }

    public static final class Result {
        public final LinkStatus status;
        public final String output;
        /** CNXN payload text, or null when the handshake did not get that far. */
        public final String banner;

        Result(LinkStatus status, String output, String banner) {
            this.status = status;
            this.output = output;
            this.banner = banner;
        }
    }

    public static final int HANDSHAKE_TIMEOUT_MS = 3000;
    public static final int PAIRING_TIMEOUT_MS = 15000;
    public static final int SHELL_TIMEOUT_MS = 5000;
    public static final int MAX_FRAME = AdbFrame.DEFAULT_MAX_PAYLOAD;
    public static final int MAX_OUTPUT = 64 * 1024;

    static final int VERSION = 0x01000001;
    static final int MAX_DATA = 4096;
    private static final Charset UTF8 = Charset.forName("UTF-8");

    private AdbShell() {
    }

    public interface ProgressListener {
        void onStage(String message);
    }

    public static Result run(Channel ch, String command, Auth auth) {
        return run(ch, command, auth, PAIRING_TIMEOUT_MS, null);
    }

    public static Result run(Channel ch, String command, Auth auth, int pairingTimeoutMs, ProgressListener listener) {
        String banner = null;
        try {
            ch.setReadTimeoutMs(HANDSHAKE_TIMEOUT_MS);
            InputStream in = ch.in();
            OutputStream out = ch.out();
            if (listener != null) listener.onStage("CNXN paketi gönderiliyor (127.0.0.1:5555)...");
            out.write(AdbFrame.encode(AdbFrame.CNXN, VERSION, MAX_DATA, ascii("host::\0")));

            int authAttempts = 0;
            boolean sentPublicKey = false;
            while (true) {
                AdbFrame f;
                try {
                    f = AdbFrame.read(in, MAX_FRAME);
                } catch (SocketTimeoutException e) {
                    if (sentPublicKey) {
                        return new Result(LinkStatus.AUTH_FAIL, "Authorization timed out — tap 'Always allow' on car screen", null);
                    }
                    throw e;
                } catch (EOFException e) {
                    if (sentPublicKey) {
                        return new Result(LinkStatus.AUTH_FAIL, "Authorization rejected by car", null);
                    }
                    throw e;
                }

                if (f.command == AdbFrame.CNXN) {
                    banner = new String(f.payload(), UTF8);
                    if (listener != null) listener.onStage("Cihaz yetkilendirildi! (" + banner + ")");
                    break;
                }
                if (f.command != AdbFrame.AUTH) {
                    return new Result(LinkStatus.ERROR, "Unexpected cmd during handshake", null);
                }
                if (f.arg0 != 1) {
                    return new Result(LinkStatus.ERROR, "Unexpected AUTH type: " + f.arg0, null);
                }
                authAttempts++;
                if (authAttempts == 1) {
                    // Attempt 1: sign the token with the key the car already trusts
                    byte[] sig = auth.sign(f.payload());
                    if (sig == null) return new Result(LinkStatus.AUTH_FAIL, "APK signature unauthorized", null);
                    if (listener != null) listener.onStage("Mevcut anahtarla imza (AUTH 2) gönderildi...");
                    out.write(AdbFrame.encode(AdbFrame.AUTH, 2, 0, sig));
                } else if (authAttempts == 2) {
                    // Attempt 2: the car does not know the key; offer it ("Allow USB debugging" pop-up)
                    if (listener != null) listener.onStage("Public key gönderildi. Araç ekranındaki pencerede 'Her zaman izin ver'e dokunun (60 sn)...");
                    ch.extendDeadlineMs(pairingTimeoutMs);
                    ch.setReadTimeoutMs(pairingTimeoutMs);
                    out.write(AdbFrame.encode(AdbFrame.AUTH, 3, 0, auth.publicKeyPayload()));
                    sentPublicKey = true;
                } else {
                    return new Result(LinkStatus.AUTH_FAIL, "Car rejected authentication", null);
                }
            }
            if (sentPublicKey) ch.setReadTimeoutMs(HANDSHAKE_TIMEOUT_MS); // the pairing wait is over

            if (command == null || command.isEmpty()) {
                return new Result(LinkStatus.SUCCESS, "Handshake OK", banner);
            }

            out.write(AdbFrame.encode(AdbFrame.OPEN, 1, 0, ascii("shell:" + command + "\0")));
            AdbFrame opened = AdbFrame.read(in, MAX_FRAME);
            if (opened.command != AdbFrame.OKAY) {
                return new Result(LinkStatus.ERROR, "Shell failed. Expected OKAY.", banner);
            }

            ByteArrayOutputStream text = new ByteArrayOutputStream();
            ch.setReadTimeoutMs(SHELL_TIMEOUT_MS);
            while (true) {
                try {
                    AdbFrame f = AdbFrame.read(in, MAX_FRAME);
                    if (f.command == AdbFrame.WRTE) {
                        if (text.size() + f.payloadLength() > MAX_OUTPUT) {
                            return new Result(LinkStatus.ERROR, "shell output longer than " + MAX_OUTPUT + " bytes", banner);
                        }
                        text.write(f.payload());
                        out.write(AdbFrame.encode(AdbFrame.OKAY, 1, f.arg0, new byte[0]));
                    } else if (f.command == AdbFrame.CLSE) {
                        break;
                    }
                    // anything else (e.g. a late OKAY) is ignored, as before
                } catch (SocketTimeoutException e) {
                    return new Result(LinkStatus.TIMEOUT, decode(text), banner);
                } catch (Exception e) {
                    return new Result(LinkStatus.ERROR, decode(text), banner);
                }
            }
            return new Result(LinkStatus.SUCCESS, decode(text), banner);

        } catch (SocketTimeoutException e) {
            return new Result(LinkStatus.TIMEOUT, null, banner);
        } catch (Exception e) {
            return new Result(LinkStatus.ERROR, e.getMessage(), banner);
        }
    }

    /** True when the banner's "features=" list names shell_v2 (logged only; this app never uses it). */
    public static boolean hasShellV2(String banner) {
        if (banner == null) return false;
        int i = banner.indexOf("features=");
        if (i < 0) return false;
        String list = banner.substring(i + "features=".length());
        int end = list.indexOf(';');
        if (end >= 0) list = list.substring(0, end);
        for (String f : list.split(",")) {
            if (f.trim().equals("shell_v2")) return true;
        }
        return false;
    }

    private static String decode(ByteArrayOutputStream b) {
        return new String(b.toByteArray(), UTF8).trim();
    }

    private static byte[] ascii(String s) {
        return s.getBytes(Charset.forName("US-ASCII"));
    }
}
