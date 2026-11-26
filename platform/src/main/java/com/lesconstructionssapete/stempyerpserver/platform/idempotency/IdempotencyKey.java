package com.lesconstructionssapete.stempyerpserver.platform.idempotency;

import java.util.Objects;
import java.util.regex.Pattern;

import com.lesconstructionssapete.stempyerpserver.platform.error.ValidationFailedException;

/**
 * One client's key for one operation: keys are per company, user and scope,
 * so two users (or two operations) never collide.
 *
 * @param scope what the key is for, e.g. {@code POST /gl/journal-entries}
 * @param key   the client's Idempotency-Key header
 */
public record IdempotencyKey(long companyId, long userId, String scope, String key) {

  /** The request header clients send. */
  public static final String HEADER = "Idempotency-Key";

  /** Printable ASCII without spaces, 1 to 100 characters (a UUID fits). */
  private static final Pattern VALID = Pattern.compile("[\\x21-\\x7E]{1,100}");

  public IdempotencyKey {
    Objects.requireNonNull(scope, "scope");
    Objects.requireNonNull(key, "key");
    if (scope.isEmpty() || scope.length() > 100) {
      throw new IllegalArgumentException("scope must be 1 to 100 characters");
    }
  }

  /**
   * The key for a request header's value; null when the header is absent.
   *
   * @throws ValidationFailedException an empty, too long or badly written key
   */
  public static IdempotencyKey fromHeader(long companyId, long userId, String scope, String header) {
    if (header == null) {
      return null;
    }
    if (!VALID.matcher(header).matches()) {
      throw ValidationFailedException.field(HEADER, "INVALID_FORMAT",
          "must be 1 to 100 printable ASCII characters without spaces, e.g. a UUID");
    }
    return new IdempotencyKey(companyId, userId, scope, header);
  }
}
