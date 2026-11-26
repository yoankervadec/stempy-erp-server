package com.lesconstructionssapete.stempyerpserver.modules.gl;

import com.lesconstructionssapete.stempyerpserver.modules.common.Entities;
import com.lesconstructionssapete.stempyerpserver.platform.entity.EntityDefinition;
import com.lesconstructionssapete.stempyerpserver.platform.entity.Field;
import com.lesconstructionssapete.stempyerpserver.platform.entity.Relation;
import com.lesconstructionssapete.stempyerpserver.platform.entity.SortKey;
import com.lesconstructionssapete.stempyerpserver.platform.entity.StandardEntity;

/**
 * An account of a company's chart of accounts (gl_accounts, docs/ARCHITECTURE.md
 * §22.2). Read through the generic entity routes (query, get, history); written
 * by AccountService, whose rules the entity framework can't express (parent
 * must be a heading, no cycles). Accounts are disabled, never deleted.
 */
public class Account extends StandardEntity {

  /** The entity name: generic routes and the audit log both use it. */
  public static final EntityDefinition<Account> DEF = Entities.define("glAccount", "gl_accounts", Account::new);

  public static final Field<Account, String> ACCOUNT_NO = DEF.string(
      "accountNo", "account_no", Account::accountNo, Account::setAccountNo)
      .required().unique().maxLength(20).filterable().sortable();

  public static final Field<Account, String> NAME = DEF.string(
      "name", "name", Account::name, Account::setName)
      .required().maxLength(100).filterable().sortable();

  public static final Field<Account, String> DESCRIPTION = DEF.string(
      "description", "description", Account::description, Account::setDescription)
      .maxLength(255).filterable();

  public static final Field<Account, AccountType> ACCOUNT_TYPE = DEF.enumeration(
      "accountType", "account_type", AccountType.class, Account::accountType, Account::setAccountType)
      .required().filterable().sortable();

  public static final Field<Account, Long> PARENT_ID = DEF.integer(
      "parentId", "parent_id", Account::parentId, Account::setParentId)
      .filterable();

  /** Lets clients filter and sort on the heading, e.g. "parent.accountNo". */
  public static final Relation<Account, Account> PARENT_REL = DEF.manyToOne("parent", () -> Account.DEF, "parent_id");

  public static final Field<Account, Boolean> IS_HEADING = DEF.bool(
      "isHeading", "is_heading", Account::isHeading, Account::setHeading)
      .filterable();

  public static final Field<Account, Boolean> MANUAL_POSTING = DEF.bool(
      "manualPosting", "manual_posting", Account::manualPosting, Account::setManualPosting)
      .filterable();

  public static final Field<Account, Boolean> IS_ENABLED = DEF.bool(
      "isEnabled", "is_enabled", Account::isEnabled, Account::setEnabled)
      .filterable();

  /** UI hints (docs/ARCHITECTURE.md §23.4): the chart in number order. */
  static {
    DESCRIPTION.multiline();
    DEF.subtitleField(NAME)
        .listColumns(ACCOUNT_NO, NAME, ACCOUNT_TYPE, PARENT_ID, IS_HEADING, MANUAL_POSTING, IS_ENABLED)
        .defaultSort(SortKey.asc(ACCOUNT_NO));
  }

  private String accountNo;
  private String name;
  private String description;
  private AccountType accountType;
  private Long parentId;
  private boolean heading;
  private boolean manualPosting;
  private boolean enabled;

  public String accountNo() {
    return accountNo;
  }

  public void setAccountNo(String accountNo) {
    this.accountNo = accountNo;
  }

  public String name() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public String description() {
    return description;
  }

  public void setDescription(String description) {
    this.description = description;
  }

  public AccountType accountType() {
    return accountType;
  }

  public void setAccountType(AccountType accountType) {
    this.accountType = accountType;
  }

  public Long parentId() {
    return parentId;
  }

  public void setParentId(Long parentId) {
    this.parentId = parentId;
  }

  public boolean isHeading() {
    return heading;
  }

  public void setHeading(boolean heading) {
    this.heading = heading;
  }

  public boolean manualPosting() {
    return manualPosting;
  }

  public void setManualPosting(boolean manualPosting) {
    this.manualPosting = manualPosting;
  }

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }
}
