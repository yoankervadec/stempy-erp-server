package com.lesconstructionssapete.stempyerpserver.modules.gl;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import com.lesconstructionssapete.stempyerpserver.platform.db.Jdbc;
import com.lesconstructionssapete.stempyerpserver.platform.db.UnitOfWork;

/**
 * SQL for what the generic entity routes don't cover: account dimension rules
 * and dimension sets (V11). Dimensions and values themselves are generic
 * entities.
 */
public class DimensionRepository {

  /** A dimension found by code. */
  public record DimensionRow(long id, String code, boolean enabled) {
  }

  /**
   * A (dimension code, value code) pair looked up: the value fields are null
   * when the dimension has no such value.
   */
  public record PairRow(long dimensionId, String dimensionCode, boolean dimensionEnabled, Long valueId,
      String valueCode, Boolean valueEnabled) {
  }

  /** One (dimension code, value code) pair of a set. */
  public record SetEntry(String dimensionCode, String valueCode) {
  }

  /** One rule of an account, with its dimension's code. */
  public record RuleRow(long dimensionId, String dimensionCode, DimensionRule rule) {
  }

  // ---- dimensions ----

  /** The company's dimension with this code (case-insensitive, like the unique key). */
  public Optional<DimensionRow> findDimension(UnitOfWork uow, long companyId, String code) {
    return Jdbc.queryFirst(uow, """
        SELECT id, code, is_enabled FROM gl_dimensions
         WHERE company_id = ? AND code = ? AND deleted_at IS NULL""",
        rs -> new DimensionRow(rs.getLong("id"), rs.getString("code"), rs.getBoolean("is_enabled")),
        companyId, code);
  }

  /** The dimension with this code and its value with that code, if any. */
  public Optional<PairRow> findPair(UnitOfWork uow, long companyId, String dimensionCode, String valueCode) {
    return Jdbc.queryFirst(uow, """
        SELECT d.id AS dimension_id, d.code AS dimension_code, d.is_enabled AS dimension_enabled,
               v.id AS value_id, v.code AS value_code, v.is_enabled AS value_enabled
          FROM gl_dimensions d
          LEFT JOIN gl_dimension_values v
            ON v.dimension_id = d.id AND v.code = ? AND v.deleted_at IS NULL
         WHERE d.company_id = ? AND d.code = ? AND d.deleted_at IS NULL""",
        rs -> new PairRow(rs.getLong("dimension_id"), rs.getString("dimension_code"),
            rs.getBoolean("dimension_enabled"), rs.getObject("value_id", Long.class), rs.getString("value_code"),
            rs.getObject("value_enabled", Boolean.class)),
        valueCode, companyId, dimensionCode);
  }

  // ---- account rules ----

  public List<RuleRow> rules(UnitOfWork uow, long accountId) {
    return Jdbc.queryList(uow, """
        SELECT r.dimension_id, d.code, r.rule_type
          FROM gl_account_dimension_rules r JOIN gl_dimensions d ON d.id = r.dimension_id
         WHERE r.account_id = ? ORDER BY d.code""",
        rs -> new RuleRow(rs.getLong(1), rs.getString(2), DimensionRule.valueOf(rs.getString(3))), accountId);
  }

  public void deleteRules(UnitOfWork uow, long accountId) {
    Jdbc.update(uow, "DELETE FROM gl_account_dimension_rules WHERE account_id = ?", accountId);
  }

  public void insertRule(UnitOfWork uow, long accountId, long dimensionId, DimensionRule rule) {
    Jdbc.update(uow, "INSERT INTO gl_account_dimension_rules (account_id, dimension_id, rule_type) VALUES (?, ?, ?)",
        accountId, dimensionId, rule);
  }

  // ---- dimension sets ----

  /**
   * Inserts the set's row; false if a set with this hash exists (only a shared
   * lock is taken then, so concurrent first uses don't deadlock).
   */
  public boolean insertSetIfAbsent(UnitOfWork uow, long companyId, String hash) {
    return Jdbc.update(uow, "INSERT IGNORE INTO gl_dimension_sets (company_id, set_hash) VALUES (?, ?)",
        companyId, hash) == 1;
  }

  public long setId(UnitOfWork uow, long companyId, String hash) {
    return Jdbc.queryFirst(uow, "SELECT id FROM gl_dimension_sets WHERE company_id = ? AND set_hash = ?",
        rs -> rs.getLong(1), companyId, hash)
        .orElseThrow(() -> new IllegalStateException("dimension set " + hash + " not found after inserting it"));
  }

  public void insertSetEntry(UnitOfWork uow, long setId, long dimensionId, long valueId) {
    Jdbc.update(uow, "INSERT INTO gl_dimension_set_entries (set_id, dimension_id, value_id) VALUES (?, ?, ?)",
        setId, dimensionId, valueId);
  }

  /** A set's (dimension code, value code) pairs, by dimension code. */
  public List<SetEntry> setEntries(UnitOfWork uow, long setId) {
    return Jdbc.queryList(uow, """
        SELECT d.code, v.code
          FROM gl_dimension_set_entries e
          JOIN gl_dimensions d ON d.id = e.dimension_id
          JOIN gl_dimension_values v ON v.id = e.value_id
         WHERE e.set_id = ? ORDER BY d.code""", rs -> new SetEntry(rs.getString(1), rs.getString(2)), setId);
  }

  /** One rule of one of several accounts. */
  public record AccountRuleRow(long accountId, String dimensionCode, DimensionRule rule) {
  }

  /** The rules of these accounts. */
  public List<AccountRuleRow> rulesOf(UnitOfWork uow, Collection<Long> accountIds) {
    if (accountIds.isEmpty()) {
      return List.of();
    }
    return Jdbc.queryList(uow, """
        SELECT r.account_id, d.code, r.rule_type
          FROM gl_account_dimension_rules r JOIN gl_dimensions d ON d.id = r.dimension_id
         WHERE r.account_id IN (""" + String.join(", ", Collections.nCopies(accountIds.size(), "?")) + ")",
        rs -> new AccountRuleRow(rs.getLong(1), rs.getString(2), DimensionRule.valueOf(rs.getString(3))),
        accountIds.toArray());
  }
}
