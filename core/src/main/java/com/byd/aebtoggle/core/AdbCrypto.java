package com.byd.aebtoggle.core;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import javax.crypto.Cipher;

/**
 * Pure-Java ADB cryptographic primitives (RSA-2048 key generation, token signing, signature verification).
 * Used by AdbKeyStore on Android and tested in :core without an Android environment.
 */
public final class AdbCrypto {

    public static final int KEY_BITS = 2048;

    /**
     * ASN.1 prefix for SHA-1 DigestInfo (RFC 3447 / PKCS #1 v1.5):
     * 30 21 30 09 06 05 2b 0e 03 02 1a 05 00 04 14
     */
    private static final byte[] ASN1_PREFIX = new byte[]{
            0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14
    };

    private AdbCrypto() {
    }

    /**
     * Generates a fresh RSA-2048 KeyPair.
     */
    public static KeyPair generateRsa2048() throws GeneralSecurityException {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(KEY_BITS);
        return kpg.generateKeyPair();
    }

    /**
     * Parses PKCS#8 DER private key bytes and reconstructs the PrivateKey.
     */
    public static PrivateKey parsePrivateKey(byte[] pkcs8Der) throws GeneralSecurityException {
        if (pkcs8Der == null) throw new IllegalArgumentException("pkcs8Der cannot be null");
        KeyFactory kf = KeyFactory.getInstance("RSA");
        return kf.generatePrivate(new PKCS8EncodedKeySpec(pkcs8Der));
    }

    /**
     * Extracts or constructs the RSAPublicKey from an RSAPrivateKey (which implements RSAPrivateCrtKey).
     */
    public static RSAPublicKey derivePublicKey(PrivateKey privateKey) throws GeneralSecurityException {
        if (privateKey instanceof RSAPrivateCrtKey) {
            RSAPrivateCrtKey crt = (RSAPrivateCrtKey) privateKey;
            KeyFactory kf = KeyFactory.getInstance("RSA");
            return (RSAPublicKey) kf.generatePublic(new RSAPublicKeySpec(crt.getModulus(), crt.getPublicExponent()));
        }
        throw new IllegalArgumentException("PrivateKey is not an RSAPrivateCrtKey: " + (privateKey == null ? "null" : privateKey.getClass().getName()));
    }

    /**
     * Builds the AUTH_RSAPUBLICKEY payload for the given public key.
     */
    public static byte[] authPayload(RSAPublicKey publicKey, String comment) {
        if (publicKey == null) throw new IllegalArgumentException("publicKey cannot be null");
        return AdbPublicKey.authPayload(publicKey.getModulus(), publicKey.getPublicExponent(), comment);
    }

    /**
     * Signs the 20-byte ADB auth challenge token with the given RSA private key using
     * PKCS#1 v1.5 padding over SHA-1 DigestInfo.
     */
    public static byte[] signToken(PrivateKey privateKey, byte[] token) throws GeneralSecurityException {
        if (privateKey == null) throw new IllegalArgumentException("privateKey cannot be null");
        if (token == null) throw new IllegalArgumentException("token cannot be null");
        byte[] payload = new byte[ASN1_PREFIX.length + token.length];
        System.arraycopy(ASN1_PREFIX, 0, payload, 0, ASN1_PREFIX.length);
        System.arraycopy(token, 0, payload, ASN1_PREFIX.length, token.length);

        Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        cipher.init(Cipher.ENCRYPT_MODE, privateKey);
        return cipher.doFinal(payload);
    }

    /**
     * Verifies an ADB token signature against the public key by decrypting with PKCS#1 v1.5
     * and checking for ASN1_PREFIX + token.
     */
    public static boolean verifyToken(PublicKey publicKey, byte[] token, byte[] signature) {
        if (publicKey == null || token == null || signature == null) return false;
        try {
            Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
            cipher.init(Cipher.DECRYPT_MODE, publicKey);
            byte[] decrypted = cipher.doFinal(signature);
            if (decrypted.length != ASN1_PREFIX.length + token.length) return false;
            for (int i = 0; i < ASN1_PREFIX.length; i++) {
                if (decrypted[i] != ASN1_PREFIX[i]) return false;
            }
            for (int i = 0; i < token.length; i++) {
                if (decrypted[ASN1_PREFIX.length + i] != token[i]) return false;
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Calculates the colon-separated hex MD5 fingerprint of the ADB public key structure,
     * matching the exact fingerprint displayed by Android's UsbDebuggingActivity dialog.
     */
    public static String getFingerprintMd5(RSAPublicKey publicKey) {
        if (publicKey == null) return "UNKNOWN";
        try {
            byte[] struct = AdbPublicKey.encodeStruct(publicKey.getModulus(), publicKey.getPublicExponent());
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(struct);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < digest.length; i++) {
                sb.append(String.format("%02X", digest[i]));
                if (i != digest.length - 1) sb.append(":");
            }
            return sb.toString();
        } catch (Exception e) {
            return "ERROR: " + e.getMessage();
        }
    }
}
