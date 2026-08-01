package com.jbp.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * SHA-256 of a string, as lower-case hex.
 *
 * <p>Extracted from {@code EmbeddingStoreImpl} so that anything needing to know "is this the same text
 * as last time" — the store today, Story 13.5's {@code scoreVersion} next — computes it identically.
 * A second implementation of this could drift from the first without any test noticing, and the symptom
 * would be records re-embedding forever or never.
 *
 * <p>Not a security primitive here: this is change detection, not password storage.
 */
public final class TextHash {

    private static final String ALGORITHM = "SHA-256";

    private TextHash() {
    }

    public static String sha256Hex(String text) {
        try {
            byte[] digest = MessageDigest.getInstance(ALGORITHM)
                    .digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException impossibleOnAnyJvm) {
            // SHA-256 is required of every Java platform, so this cannot happen at runtime.
            throw new IllegalStateException(ALGORITHM + " is unavailable", impossibleOnAnyJvm);
        }
    }
}
