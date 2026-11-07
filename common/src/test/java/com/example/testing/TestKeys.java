package com.example.testing;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.util.Base64;

public final class TestKeys {
    private TestKeys() {}

    public static Path[] create() {
        try {
            var generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            var pair = generator.generateKeyPair();
            var directory = Files.createTempDirectory("hotel-booking-test-keys");
            var privateKey = directory.resolve("private.pem");
            var publicKey = directory.resolve("public.pem");
            Files.writeString(privateKey, pem("PRIVATE KEY", pair.getPrivate().getEncoded()));
            Files.writeString(publicKey, pem("PUBLIC KEY", pair.getPublic().getEncoded()));
            directory.toFile().deleteOnExit();
            privateKey.toFile().deleteOnExit();
            publicKey.toFile().deleteOnExit();
            return new Path[] {privateKey, publicKey};
        } catch (Exception failure) {
            throw new IllegalStateException("Cannot create test keys", failure);
        }
    }

    private static String pem(String type, byte[] data) {
        return "-----BEGIN "
                + type
                + "-----\n"
                + Base64.getEncoder().encodeToString(data)
                + "\n-----END "
                + type
                + "-----\n";
    }
}
