package com.lesconstructionssapete.stempyerpserver.modules.tax;

import java.time.LocalDate;
import java.util.List;

import com.lesconstructionssapete.stempyerpserver.modules.tax.TaxApi.TaxKind;
import com.lesconstructionssapete.stempyerpserver.modules.tax.TaxRuleSet.GroupLine;
import com.lesconstructionssapete.stempyerpserver.modules.tax.TaxRuleSet.Rate;
import com.lesconstructionssapete.stempyerpserver.modules.tax.TaxRuleSet.Rule;
import com.lesconstructionssapete.stempyerpserver.platform.db.Jdbc;
import com.lesconstructionssapete.stempyerpserver.platform.db.UnitOfWork;

/**
 * Reads the tax data (V7 tables), enabled rows only. Install-wide: no
 * company filter, government rates are the same for every company.
 *
 * A row that refers to a disabled row drops out with it: a group line whose
 * group or rate is disabled, a rule whose jurisdiction, category or group is.
 * Disabling a group therefore makes its rules disappear (TAX_RULE_NOT_FOUND),
 * rather than leaving rules that point nowhere.
 */
public class TaxRepository {

  public List<String> jurisdictionCodes(UnitOfWork uow) {
    return Jdbc.queryList(uow, "SELECT code FROM tax_jurisdictions WHERE is_enabled", rs -> rs.getString(1));
  }

  public List<String> categoryCodes(UnitOfWork uow) {
    return Jdbc.queryList(uow, "SELECT code FROM tax_categories WHERE is_enabled", rs -> rs.getString(1));
  }

  public List<String> groupCodes(UnitOfWork uow) {
    return Jdbc.queryList(uow, "SELECT code FROM tax_groups WHERE is_enabled", rs -> rs.getString(1));
  }

  public List<Rate> rates(UnitOfWork uow) {
    return Jdbc.queryList(uow, """
        SELECT code, description, tax_kind, rate, is_compound, recoverable_percent, calculation_order,
               valid_from_date, valid_to_date
        FROM tax_rates WHERE is_enabled""",
        rs -> new Rate(
            rs.getString("code"),
            rs.getString("description"),
            TaxKind.valueOf(rs.getString("tax_kind")),
            rs.getBigDecimal("rate"),
            rs.getBoolean("is_compound"),
            rs.getBigDecimal("recoverable_percent"),
            rs.getInt("calculation_order"),
            rs.getObject("valid_from_date", LocalDate.class),
            rs.getObject("valid_to_date", LocalDate.class)));
  }

  public List<GroupLine> groupLines(UnitOfWork uow) {
    return Jdbc.queryList(uow, """
        SELECT g.code AS group_code, r.code AS rate_code, l.sort_order
        FROM tax_group_lines l
        JOIN tax_groups g ON g.id = l.tax_group_id AND g.is_enabled
        JOIN tax_rates r ON r.id = l.tax_rate_id AND r.is_enabled""",
        rs -> new GroupLine(rs.getString("group_code"), rs.getString("rate_code"), rs.getInt("sort_order")));
  }

  public List<Rule> rules(UnitOfWork uow) {
    return Jdbc.queryList(uow, """
        SELECT j.code AS jurisdiction, c.code AS category, g.code AS group_code, u.valid_from_date, u.valid_to_date
        FROM tax_rules u
        JOIN tax_jurisdictions j ON j.id = u.jurisdiction_id AND j.is_enabled
        JOIN tax_categories c ON c.id = u.tax_category_id AND c.is_enabled
        LEFT JOIN tax_groups g ON g.id = u.tax_group_id
        WHERE u.is_enabled AND (u.tax_group_id IS NULL OR g.is_enabled)""",
        rs -> new Rule(
            rs.getString("jurisdiction"),
            rs.getString("category"),
            rs.getString("group_code"),
            rs.getObject("valid_from_date", LocalDate.class),
            rs.getObject("valid_to_date", LocalDate.class)));
  }
}
