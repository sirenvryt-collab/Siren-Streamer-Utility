package com.donutstreamer.music;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/** PKCE helpers for Spotify's authorization-code flow (no client secret is ever needed or stored). */
final class Pkce {
    private Pkce() {}

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~";

    /** 64 random unreserved characters (the spec allows 43-128). */
    static String newVerifier() {
        StringBuilder sb = new StringBuilder(64);
        for (int i = 0; i < 64; i++) sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        return sb.toString();
    }

    /** base64url(SHA-256(verifier)) without padding. */
    static String challenge(String verifier) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);   // SHA-256 is required on every JVM
        }
    }

    static String newState() {
        byte[] b = new byte[16];
        RANDOM.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    /**
     * Reads one query parameter from an HTTP request line such as
     * "GET /callback?code=abc&state=xyz HTTP/1.1". Returns null if absent.
     */
    static String queryParam(String requestLine, String name) {
        if (requestLine == null) return null;
        int q = requestLine.indexOf('?');
        if (q < 0) return null;
        int end = requestLine.indexOf(' ', q);
        String query = requestLine.substring(q + 1, end < 0 ? requestLine.length() : end);
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            String key = eq < 0 ? pair : pair.substring(0, eq);
            if (key.equals(name)) {
                String v = eq < 0 ? "" : pair.substring(eq + 1);
                return URLDecoder.decode(v, StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    /** True for "GET /callback..." request lines (browsers also ask for /favicon.ico). */
    static boolean isCallback(String requestLine) {
        return requestLine != null && requestLine.startsWith("GET /callback");
    }
}
