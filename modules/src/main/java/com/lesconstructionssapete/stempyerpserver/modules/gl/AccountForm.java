package com.lesconstructionssapete.stempyerpserver.modules.gl;

import java.util.Arrays;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.lesconstructionssapete.stempyerpserver.platform.error.Validation;
import com.lesconstructionssapete.stempyerpserver.platform.error.ValidationFailedException;

/**
 * Turns account request bodies into AccountFields: every field is type-checked
 * and every problem is reported at once. Rules that need the database (unique
 * number, parent, cycles, children) are the operations'.
 *
 * PATCH semantics: a field left out keeps its value; description and parentId
 * may be sent as null (cleared).
 */
final class AccountForm {

  /** Account numbers: letters, digits, '.', '-', '_'; starting with a letter or digit. */
  static final Pattern ACCOUNT_NO = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,19}");

  /** Sent back by the API, never accepted. */
  private static final Set<String> READ_ONLY = Set.of("id", "companyId", "createdAt", "createdBy", "updatedAt",
      "updatedBy", "deletedAt");

  /** A parsed POST or PATCH body; {@code version} is set on change. */
  record Result(AccountFields fields, Integer version) {
  }

  private AccountForm() {
  }

  /**
   * @param base the stored account (PATCH) or null (POST)
   * @throws ValidationFailedException listing every problem
   */
  static Result apply(JsonNode body, AccountFields base) {
    if (body == null || !body.isObject()) {
      throw new ValidationFailedException("The request body must be a JSON object");
    }
    boolean creating = base == null;
    var v = Validation.check();
    String accountNo = creating ? null : base.accountNo();
    String name = creating ? null : base.name();
    String description = creating ? null : base.description();
    AccountType type = creating ? null : base.accountType();
    Long parentId = creating ? null : base.parentId();
    boolean heading = !creating && base.isHeading();
    boolean manualPosting = creating || base.manualPosting();
    boolean enabled = creating || base.isEnabled();
    Integer version = null;

    for (Iterator<Map.Entry<String, JsonNode>> it = body.fields(); it.hasNext();) {
      var e = it.next();
      String f = e.getKey();
      JsonNode n = e.getValue();
      switch (f) {
        case "accountNo" -> accountNo = text(v, f, n, false, accountNo);
        case "name" -> name = text(v, f, n, false, name);
        case "description" -> description = text(v, f, n, true, description);
        case "accountType" -> type = accountType(v, f, n, type);
        case "parentId" -> {
          if (n.isNull()) {
            parentId = null;
          } else if (n.isIntegralNumber() && n.canConvertToLong() && n.longValue() > 0) {
            parentId = n.longValue();
          } else {
            v.add(f, "INVALID_TYPE", "must be an account id or null");
          }
        }
        case "isHeading" -> heading = bool(v, f, n, heading);
        case "manualPosting" -> manualPosting = bool(v, f, n, manualPosting);
        case "isEnabled" -> enabled = bool(v, f, n, enabled);
        case "version" -> {
          if (creating) {
            v.add(f, "READ_ONLY", "is set by the server");
          } else if (n.isIntegralNumber() && n.canConvertToInt() && n.intValue() >= 0) {
            version = n.intValue();
          } else {
            v.add(f, "INVALID_TYPE", "must be a whole number");
          }
        }
        default -> v.add(f, READ_ONLY.contains(f) ? "READ_ONLY" : "UNKNOWN_FIELD",
            READ_ONLY.contains(f) ? "is set by the server" : "unknown field '" + f + "'");
      }
    }
    if (creating) {
      v.required("accountNo", accountNo).required("name", name).required("accountType", type);
    } else if (!body.has("version")) {
      v.add("version", "REQUIRED", "send the version you last read");
    }
    if (accountNo != null && !ACCOUNT_NO.matcher(accountNo).matches()) {
      v.add("accountNo", "INVALID_FORMAT",
          "letters, digits, '.', '-' and '_', starting with a letter or digit, at most 20 characters");
    }
    v.maxLength("name", name, 100).maxLength("description", description, 255);
    v.throwIfInvalid();
    return new Result(new AccountFields(accountNo, name, description, type, parentId, heading, manualPosting,
        enabled), version);
  }

  private static String text(Validation v, String field, JsonNode n, boolean nullable, String current) {
    if (n.isNull() || (n.isTextual() && n.textValue().isBlank())) {
      if (!nullable) {
        v.add(field, "REQUIRED", "is required");
        return current;
      }
      return null;
    }
    if (!n.isTextual()) {
      v.add(field, "INVALID_TYPE", "must be a string");
      return current;
    }
    return n.textValue().strip();
  }

  private static boolean bool(Validation v, String field, JsonNode n, boolean current) {
    if (n.isBoolean()) {
      return n.booleanValue();
    }
    v.add(field, "INVALID_TYPE", "must be true or false");
    return current;
  }

  private static AccountType accountType(Validation v, String field, JsonNode n, AccountType current) {
    if (n.isTextual()) {
      try {
        return AccountType.valueOf(n.textValue().strip().toUpperCase(Locale.ROOT));
      } catch (IllegalArgumentException e) {
        // reported below
      }
    }
    v.add(field, "INVALID_VALUE", "must be one of " + Arrays.toString(AccountType.values()));
    return current;
  }
}
