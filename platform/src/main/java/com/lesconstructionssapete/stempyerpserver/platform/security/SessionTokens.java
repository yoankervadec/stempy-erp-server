package com.lesconstructionssapete.stempyerpserver.platform.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Session tokens: 256 random bits, given to the client once at login. The
 * server only ever stores SHA-256(token), so a leaked sessions table cannot be
 * replayed. A fast hash is right here (unlike passwords): the token is random,
 * so there is nothing to brute-force.
 */
public final class SessionTokens {

  private static final SecureRandom RANDOM = new SecureRandom();
  private static final int TOKEN_BYTES = 32;

  private SessionTokens() {
  }

  /** 43 URL-safe characters. */
  public static String newToken() {
    byte[] bytes = new byte[TOKEN_BYTES];
    RANDOM.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  /** What gets stored and looked up: 32 bytes. */
  public static byte[] hash(String token) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable", e); // every JDK has it
    }
  }
}
