package com.byd.aebtoggle;

import android.content.Context;
import android.os.SystemClock;
import android.util.Log;

import com.byd.aebtoggle.core.AdbCrypto;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * Multi-Location Resilient ADB Key Store.
 *
 * Manages an RSA-2048 key pair uniquely generated for this installation and redundantly
 * persisted across multiple resilient safe storage locations:
 *  1. Normal private storage: Context.getFilesDir()
 *  2. Device Protected Storage: Context.createDeviceProtectedStorageContext().getFilesDir()
 *  3. App external files: Context.getExternalFilesDir(null)
 *  4. App external media: Context.getExternalMediaDirs()
 *
 * Retries on early boot (in case storage is mounting).
 * Derives and logs the MD5 fingerprint formatted identically to Android's UsbDebuggingActivity dialog.
 */
public final class AdbKeyStore {

    private static final String TAG = "AdbKeyStore";
    private static final String KEY_FILE_NAME = "adb_key.pk8";
    private static final String DEFAULT_COMMENT = "wireless@adb";

    private static volatile AdbKeyStore instance;

    private final PrivateKey privateKey;
    private final RSAPublicKey publicKey;
    private final byte[] publicKeyPayload;
    private final String fingerprintMd5;
    private final String keySource;
    private final File activeFile;

    private AdbKeyStore(PrivateKey privateKey, RSAPublicKey publicKey, byte[] publicKeyPayload,
                        String fingerprintMd5, String keySource, File activeFile) {
        this.privateKey = privateKey;
        this.publicKey = publicKey;
        this.publicKeyPayload = publicKeyPayload;
        this.fingerprintMd5 = fingerprintMd5;
        this.keySource = keySource;
        this.activeFile = activeFile;
    }

    public static AdbKeyStore getInstance(Context context) {
        if (instance == null) {
            synchronized (AdbKeyStore.class) {
                if (instance == null) {
                    instance = loadOrGenerate(context.getApplicationContext());
                }
            }
        }
        return instance;
    }

    public static AdbKeyStore get() {
        AdbKeyStore s = instance;
        if (s == null) {
            Context ctx = MainApplication.getAppContext();
            if (ctx != null) return getInstance(ctx);
            throw new IllegalStateException("AdbKeyStore not initialized and Application Context is unavailable");
        }
        return s;
    }

    public byte[] sign(byte[] token) throws Exception {
        return AdbCrypto.signToken(privateKey, token);
    }

    public byte[] getPublicKeyPayload() {
        return publicKeyPayload.clone();
    }

    public RSAPublicKey getPublicKey() {
        return publicKey;
    }

    public PrivateKey getPrivateKey() {
        return privateKey;
    }

    public String getFingerprintMd5() {
        return fingerprintMd5;
    }

    public String getKeySource() {
        return keySource;
    }

    public File getActiveFile() {
        return activeFile;
    }

    public String getKeyDetails() {
        if (activeFile != null && activeFile.exists()) {
            return "Kaynak: " + keySource +
                    "\nDosya: " + activeFile.getAbsolutePath() +
                    "\nBoyut: " + activeFile.length() + " B" +
                    "\nSon Değişiklik: " + new Date(activeFile.lastModified()) +
                    "\nRSA MD5 Parmak İzi: " + fingerprintMd5;
        }
        return "Kaynak: " + keySource + "\nRSA MD5 Parmak İzi: " + fingerprintMd5;
    }

    private static List<File> getSafeKeyLocations(Context context) {
        List<File> list = new ArrayList<>();
        // 1. Normal private storage
        try {
            File f = context.getFilesDir();
            if (f != null) list.add(new File(f, KEY_FILE_NAME));
        } catch (Throwable ignored) {}

        // 2. Device protected storage (Direct boot / survives before unlock)
        try {
            Context dpc = context.createDeviceProtectedStorageContext();
            if (dpc != null && dpc.getFilesDir() != null) {
                list.add(new File(dpc.getFilesDir(), KEY_FILE_NAME));
            }
        } catch (Throwable ignored) {}

        // 3. App external files dir
        try {
            File ext = context.getExternalFilesDir(null);
            if (ext != null) list.add(new File(ext, KEY_FILE_NAME));
        } catch (Throwable ignored) {}

        // 4. App external media dir
        try {
            File[] mediaDirs = context.getExternalMediaDirs();
            if (mediaDirs != null && mediaDirs.length > 0 && mediaDirs[0] != null) {
                list.add(new File(mediaDirs[0], KEY_FILE_NAME));
            }
        } catch (Throwable ignored) {}

        return list;
    }

    private static AdbKeyStore loadOrGenerate(Context context) {
        List<File> safeLocations = getSafeKeyLocations(context);
        File loadedFile = null;
        byte[] keyBytes = null;

        // Step 1: Retry loop for early boot: check up to 5 times (2.5 seconds max)
        for (int attempt = 1; attempt <= 5; attempt++) {
            for (File file : safeLocations) {
                if (file != null && file.exists() && file.length() > 0) {
                    try {
                        byte[] der = readFile(file);
                        // Validate by parsing key
                        PrivateKey priv = AdbCrypto.parsePrivateKey(der);
                        AdbCrypto.derivePublicKey(priv);
                        keyBytes = der;
                        loadedFile = file;
                        break;
                    } catch (Exception e) {
                        Log.w(TAG, "Failed reading key from " + file + ": " + e.getMessage());
                        StateStore.event("AdbKeyStore: " + file.getName() + " okunamadı: " + e.getMessage());
                    }
                }
            }
            if (keyBytes != null) break;
            if (attempt < 5) {
                SystemClock.sleep(500);
            }
        }

        // Step 2: IF FOUND IN ANY LOCATION -> Load and synchronize across all safe locations
        if (keyBytes != null && loadedFile != null) {
            try {
                PrivateKey priv = AdbCrypto.parsePrivateKey(keyBytes);
                RSAPublicKey pub = AdbCrypto.derivePublicKey(priv);
                byte[] payload = AdbCrypto.authPayload(pub, DEFAULT_COMMENT);
                String fingerprint = AdbCrypto.getFingerprintMd5(pub);
                String source = "diskten yüklendi (" + loadedFile.getAbsolutePath() + ")";

                // Replicate key to any safe location where it is missing
                for (File file : safeLocations) {
                    if (file != null && (!file.exists() || file.length() == 0)) {
                        try {
                            saveFileAtomically(file, keyBytes);
                            Log.d(TAG, "Replicated key to backup location: " + file);
                            StateStore.event("AdbKeyStore: anahtar yedeklendi -> " + file.getAbsolutePath());
                        } catch (Exception e) {
                            Log.w(TAG, "Failed replicating key to " + file + ": " + e.getMessage());
                        }
                    }
                }

                StateStore.event("AdbKeyStore: anahtar DİSKTEN YÜKLENDİ: " + loadedFile.getAbsolutePath());
                StateStore.event("AdbKeyStore: Parmak izi (MD5): " + fingerprint);
                Log.i(TAG, "AdbKey loaded from " + loadedFile + ", fingerprint=" + fingerprint);
                return new AdbKeyStore(priv, pub, payload, fingerprint, source, loadedFile);
            } catch (Exception e) {
                Log.e(TAG, "Error deriving key after read: " + e.getMessage());
                StateStore.event("AdbKeyStore: okunan anahtar geçersiz: " + e.getMessage());
            }
        }

        // Step 3: IF NOT FOUND ANYWHERE -> Generate ONCE and write to all safe locations
        try {
            KeyPair kp = AdbCrypto.generateRsa2048();
            PrivateKey priv = kp.getPrivate();
            RSAPublicKey pub = (RSAPublicKey) kp.getPublic();
            byte[] payload = AdbCrypto.authPayload(pub, DEFAULT_COMMENT);
            String fingerprint = AdbCrypto.getFingerprintMd5(pub);
            byte[] encodedPriv = priv.getEncoded();
            String source = "yeni üretildi";
            File primaryFile = safeLocations.isEmpty() ? null : safeLocations.get(0);

            for (File file : safeLocations) {
                if (file != null) {
                    try {
                        saveFileAtomically(file, encodedPriv);
                        // Read back to verify
                        byte[] readBack = readFile(file);
                        if (readBack.length == encodedPriv.length) {
                            Log.i(TAG, "AdbKey successfully written & verified at: " + file);
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Failed writing key to " + file + ": " + e.getMessage());
                        StateStore.event("AdbKeyStore: YAZMA HATASI (" + file + "): " + e.getMessage());
                    }
                }
            }

            StateStore.event("AdbKeyStore: YENİ ANAHTAR ÜRETİLDİ");
            StateStore.event("AdbKeyStore: Parmak izi (MD5): " + fingerprint);
            Log.i(TAG, "Generated fresh RSA-2048 ADB key, fingerprint=" + fingerprint);
            return new AdbKeyStore(priv, pub, payload, fingerprint, source, primaryFile);
        } catch (Exception e) {
            throw new RuntimeException("AdbKeyStore: failed to generate/persist ADB key pair", e);
        }
    }

    private static byte[] readFile(File file) throws IOException {
        long len = file.length();
        if (len > 16384) throw new IOException("Key file unexpectedly large: " + len);
        byte[] data = new byte[(int) len];
        try (FileInputStream fis = new FileInputStream(file)) {
            int offset = 0;
            while (offset < data.length) {
                int read = fis.read(data, offset, data.length - offset);
                if (read < 0) break;
                offset += read;
            }
        }
        return data;
    }

    private static void saveFileAtomically(File targetFile, byte[] data) throws IOException {
        File parent = targetFile.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        File tmpFile = new File(parent, targetFile.getName() + ".tmp");
        try (FileOutputStream fos = new FileOutputStream(tmpFile)) {
            fos.write(data);
            fos.flush();
        }
        if (!tmpFile.renameTo(targetFile)) {
            if (targetFile.exists() && !targetFile.delete()) {
                throw new IOException("Failed to replace existing key file " + targetFile);
            }
            if (!tmpFile.renameTo(targetFile)) {
                throw new IOException("Failed to rename temporary key file to " + targetFile);
            }
        }
    }
}
