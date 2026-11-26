package com.lesconstructionssapete.stempyerpserver.modules.gl;

import java.util.Map;

import com.lesconstructionssapete.stempyerpserver.platform.write.EntityChanged.FieldChange;
import com.lesconstructionssapete.stempyerpserver.platform.write.FieldChanges;

/**
 * The columns of an account a user may set. Component names are the API field
 * names (Account.DEF), so audit changes read, and are masked, like the generic
 * entity's.
 *
 * @param parentId null: a top-level account
 */
public record AccountFields(
    String accountNo,
    String name,
    String description,
    AccountType accountType,
    Long parentId,
    boolean isHeading,
    boolean manualPosting,
    boolean isEnabled) {

  /** The changes between two versions, for the audit log. {@code before} null: a creation. */
  public static Map<String, FieldChange> changes(AccountFields before, AccountFields after) {
    return FieldChanges.records(before, after);
  }
}
