package com.byd.aebtoggle.core;

import java.math.BigInteger;
import java.nio.charset.Charset;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;

final class AdbCryptoTest {
    private AdbCryptoTest() {
    }

    static void run() throws Exception {
        T.section("AdbCrypto: key generation and derivation");
        KeyPair kp = AdbCrypto.generateRsa2048();
        T.isTrue("key pair generated", kp != null);
        RSAPublicKey pub = (RSAPublicKey) kp.getPublic();
        T.eq("modulus bit length", 2048, pub.getModulus().bitLength());
        T.eq("public exponent", BigInteger.valueOf(65537), pub.getPublicExponent());

        byte[] pkcs8 = kp.getPrivate().getEncoded();
        T.isTrue("PKCS#8 DER bytes produced", pkcs8 != null && pkcs8.length > 0);

        PrivateKey parsedPriv = AdbCrypto.parsePrivateKey(pkcs8);
        RSAPublicKey derivedPub = AdbCrypto.derivePublicKey(parsedPriv);
        T.eq("derived modulus matches original", pub.getModulus(), derivedPub.getModulus());
        T.eq("derived exponent matches original", pub.getPublicExponent(), derivedPub.getPublicExponent());

        T.section("AdbCrypto: auth payload generation");
        byte[] payload = AdbCrypto.authPayload(derivedPub, "wireless@adb");
        String payloadStr = new String(payload, Charset.forName("US-ASCII"));
        T.isTrue("payload ends with comment and NUL", payloadStr.endsWith(" wireless@adb\0"));
        String b64 = payloadStr.substring(0, payloadStr.indexOf(' '));
        byte[] decodedStruct = Base64.getDecoder().decode(b64);
        T.eq("decoded struct is 524 bytes", AdbPublicKey.STRUCT_BYTES, decodedStruct.length);

        T.section("AdbCrypto: token signing and verification");
        byte[] token = new byte[]{
                1, 2, 3, 4, 5, 6, 7, 8, 9, 10,
                11, 12, 13, 14, 15, 16, 17, 18, 19, 20
        };
        byte[] sig = AdbCrypto.signToken(parsedPriv, token);
        T.isTrue("signature produced", sig != null && sig.length == 256);
        T.isTrue("valid signature verifies", AdbCrypto.verifyToken(derivedPub, token, sig));

        byte[] wrongToken = new byte[]{
                1, 2, 3, 4, 5, 6, 7, 8, 9, 10,
                11, 12, 13, 14, 15, 16, 17, 18, 19, 21 // changed last byte
        };
        T.isTrue("wrong token rejected", !AdbCrypto.verifyToken(derivedPub, wrongToken, sig));

        byte[] badSig = sig.clone();
        badSig[0] ^= 0x55;
        T.isTrue("corrupted signature rejected", !AdbCrypto.verifyToken(derivedPub, token, badSig));

        T.section("AdbCrypto: bad input validation");
        T.throwsA("null pkcs8", IllegalArgumentException.class, () -> AdbCrypto.parsePrivateKey(null));
        T.throwsA("null privateKey to derive", IllegalArgumentException.class, () -> AdbCrypto.derivePublicKey(null));
        T.throwsA("null publicKey to authPayload", IllegalArgumentException.class, () -> AdbCrypto.authPayload(null, "test"));
        T.throwsA("null privateKey to sign", IllegalArgumentException.class, () -> AdbCrypto.signToken(null, token));
        T.throwsA("null token to sign", IllegalArgumentException.class, () -> AdbCrypto.signToken(parsedPriv, null));

        T.section("AdbCrypto: fingerprint MD5");
        String fp = AdbCrypto.getFingerprintMd5(derivedPub);
        T.isTrue("fingerprint matches XX:XX pattern", fp.matches("([0-9A-F]{2}:){15}[0-9A-F]{2}"));
    }
}
