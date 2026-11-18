package com.lesconstructionssapete.stempyerpserver.platform.security;

import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * Password hashing with PBKDF2-HMAC-SHA256 (in the JDK, no extra dependency).
 *
 * Stored format, self-describing so the cost can be raised later without
 * breaking existing hashes:
 *
 * <pre>
 * pbkdf2-sha256$&lt;iterations&gt;$&lt;base64 salt&gt;$&lt;base64 hash&gt;
 * </pre>
 *
 * 600,000 iterations is the OWASP recommendation for PBKDF2-HMAC-SHA256. It
 * costs a few hundred milliseconds per login, on purpose: that is what makes a
 * stolen hash table expensive to crack. Use needsRehash() to upgrade old
 * hashes after a successful login.
 */
public final class PasswordHasher {

  public static final int DEFAULT_ITERATIONS = 600_000;

  private static final String PREFIX = "pbkdf2-sha256";
  private static final String ALGORITHM = "PBKDF2WithHmacSHA256";
  private static final int SALT_BYTES = 16;
  private static final int HASH_BITS = 256;
  /** Refuse absurd costs read from the database (corrupt row = no DoS). */
  private static final int MAX_ITERATIONS = 10_000_000;

  private static final SecureRandom RANDOM = new SecureRandom();

  private final int iterations;

  public PasswordHasher() {
    this(DEFAULT_ITERATIONS);
  }

  /** Lower iteration counts are for tests only. */
  public PasswordHasher(int iterations) {
    if (iterations < 1 || iterations > MAX_ITERATIONS) {
      throw new IllegalArgumentException("iterations out of range: " + iterations);
    }
    this.iterations = iterations;
  }

  public String hash(char[] password) {
    byte[] salt = new byte[SALT_BYTES];
    RANDOM.nextBytes(salt);
    byte[] hash = derive(password, salt, iterations);
    Base64.Encoder b64 = Base64.getEncoder().withoutPadding();
    return PREFIX + "$" + iterations + "$" + b64.encodeToString(salt) + "$" + b64.encodeToString(hash);
  }

  /**
   * True if the password matches. A malformed stored value is treated as "does
   * not match", never as an error the caller could leak.
   */
  public boolean verify(char[] password, String stored) {
    Parsed p = parse(stored);
    if (p == null) {
      return false;
    }
    byte[] actual = derive(password, p.salt, p.iterations);
    return MessageDigest.isEqual(actual, p.hash); // constant time
  }

  /** True if the stored hash uses a weaker setting than this hasher. */
  public boolean needsRehash(String stored) {
    Parsed p = parse(stored);
    return p == null || p.iterations < iterations;
  }

  private record Parsed(int iterations, byte[] salt, byte[] hash) {
  }

  private static Parsed parse(String stored) {
    if (stored == null) {
      return null;
    }
    String[] parts = stored.split("\\$");
    if (parts.length != 4 || !PREFIX.equals(parts[0])) {
      return null;
    }
    try {
      int iterations = Integer.parseInt(parts[1]);
      if (iterations < 1 || iterations > MAX_ITERATIONS) {
        return null;
      }
      Base64.Decoder b64 = Base64.getDecoder();
      byte[] salt = b64.decode(parts[2]);
      byte[] hash = b64.decode(parts[3]);
      if (salt.length == 0 || hash.length != HASH_BITS / 8) {
        return null;
      }
      return new Parsed(iterations, salt, hash);
    } catch (IllegalArgumentException e) { // NumberFormatException included
      return null;
    }
  }

  private static byte[] derive(char[] password, byte[] salt, int iterations) {
    PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, HASH_BITS);
    try {
      return SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).getEncoded();
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(ALGORITHM + " unavailable", e); // every JDK has it
    } finally {
      spec.clearPassword();
    }
  }

  /** Overwrites a password buffer once it is no longer needed. */
  public static void wipe(char[] password) {
    if (password != null) {
      Arrays.fill(password, '\0');
    }
  }
}
