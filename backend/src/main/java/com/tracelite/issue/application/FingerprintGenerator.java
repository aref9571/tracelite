package com.tracelite.issue.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.stereotype.Component;

@Component
public class FingerprintGenerator {

    private static final String ALGORITHM = "SHA-256";

    public String generate(String exceptionType) {
        byte[] hash = sha256().digest(exceptionType.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(hash);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance(ALGORITHM);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(ALGORITHM + " is not available", exception);
        }
    }
}
