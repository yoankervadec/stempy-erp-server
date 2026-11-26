package com.lesconstructionssapete.stempyerpserver.modules.gl;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;

import com.lesconstructionssapete.stempyerpserver.platform.db.Jdbc;
import com.lesconstructionssapete.stempyerpserver.platform.db.UnitOfWork;

/** SQL for gl_accounts writes. Reads for clients go through the generic entity routes. */
public class AccountRepository {

  private static final String COLUMNS = """
      id, version, company_id, account_no, name, description, account_type, parent_id, is_heading,
      manual_posting, is_enabled""";

  private static StoredAccount map(ResultSet rs) throws SQLException {
    var fields = new AccountFields(rs.getString("account_no"), rs.getString("name"), rs.getString("description"),
        AccountType.valueOf(rs.getString("account_type")), rs.getObject("parent_id", Long.class),
        rs.getBoolean("is_heading"), rs.getBoolean("manual_posting"), rs.getBoolean("is_enabled"));
    return new StoredAccount(rs.getLong("id"), rs.getInt("version"), rs.getLong("company_id"), fields);
  }

  public Optional<StoredAccount> find(UnitOfWork uow, long id) {
    return Jdbc.queryFirst(uow, "SELECT " + COLUMNS + " FROM gl_accounts WHERE id = ? AND deleted_at IS NULL",
        AccountRepository::map, id);
  }

  /** Locks the row until the transaction ends. */
  public Optional<StoredAccount> findForUpdate(UnitOfWork uow, long id) {
    return Jdbc.queryFirst(uow,
        "SELECT " + COLUMNS + " FROM gl_accounts WHERE id = ? AND deleted_at IS NULL FOR UPDATE",
        AccountRepository::map, id);
  }

  /**
   * A row by id with a shared lock: a parent can't stop being a heading (or
   * move) while a child is attached to it.
   */
  public Optional<StoredAccount> findForShare(UnitOfWork uow, long id) {
    return Jdbc.queryFirst(uow,
        "SELECT " + COLUMNS + " FROM gl_accounts WHERE id = ? AND deleted_at IS NULL FOR SHARE",
        AccountRepository::map, id);
  }

  /** Whether another account of the company uses this number (case-insensitive, like the unique key). */
  public boolean accountNoTaken(UnitOfWork uow, long companyId, String accountNo, long excludeId) {
    return Jdbc.queryFirst(uow, "SELECT 1 FROM gl_accounts WHERE company_id = ? AND account_no = ? AND id <> ?",
        rs -> true, companyId, accountNo, excludeId).isPresent();
  }

  /** Whether any account has this one as its parent. */
  public boolean hasChildren(UnitOfWork uow, long id) {
    return Jdbc.queryFirst(uow, "SELECT 1 FROM gl_accounts WHERE parent_id = ? LIMIT 1", rs -> true, id)
        .isPresent();
  }

  public long insert(UnitOfWork uow, long companyId, AccountFields f, long userId, Instant now) {
    return Jdbc.insert(uow, """
        INSERT INTO gl_accounts (company_id, account_no, name, description, account_type, parent_id, is_heading,
          manual_posting, is_enabled, created_at, created_by, updated_at, updated_by)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
        companyId, f.accountNo(), f.name(), f.description(), f.accountType(), f.parentId(), f.isHeading(),
        f.manualPosting(), f.isEnabled(), now, userId, now, userId);
  }

  /** Replaces the editable columns if the row is still at {@code version}. Returns the rows changed. */
  public int update(UnitOfWork uow, long id, int version, AccountFields f, long userId, Instant now) {
    return Jdbc.update(uow, """
        UPDATE gl_accounts
           SET account_no = ?, name = ?, description = ?, account_type = ?, parent_id = ?, is_heading = ?,
               manual_posting = ?, is_enabled = ?, version = version + 1, updated_at = ?, updated_by = ?
         WHERE id = ? AND version = ?""",
        f.accountNo(), f.name(), f.description(), f.accountType(), f.parentId(), f.isHeading(), f.manualPosting(),
        f.isEnabled(), now, userId, id, version);
  }
}
