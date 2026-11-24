package com.lesconstructionssapete.stempyerpserver.modules.tax;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import com.lesconstructionssapete.stempyerpserver.modules.tax.TaxApi.TaxKind;
import com.lesconstructionssapete.stempyerpserver.platform.error.ConflictException;

/**
 * The tax data in memory (enabled rows of the V7 tables), with the two
 * lookups the calculator needs. Pure: built by the repository from the
 * database, or by hand in tests.
 *
 * Data errors are told apart:
 * <ul>
 * <li>a gap (no rule, or no rate valid on the date) is a ConflictException
 * TAX_RULE_NOT_FOUND / TAX_RATE_NOT_FOUND: the data does not cover the
 * request, e.g. a date before the seeded rates;</li>
 * <li>an ambiguity (two rules for one date, two rates of one kind in a group
 * on one date) or a dangling reference is an IllegalStateException: the data
 * is wrong and must be fixed.</li>
 * </ul>
 */
public final class TaxRuleSet {

  /** A tax_rates row. */
  public record Rate(String code, String description, TaxKind kind, BigDecimal percent, boolean compound,
      BigDecimal recoverablePercent, int calculationOrder, LocalDate validFrom, LocalDate validTo) {
    public Rate {
      Objects.requireNonNull(code, "code");
      Objects.requireNonNull(kind, "kind");
      Objects.requireNonNull(percent, "percent");
      Objects.requireNonNull(recoverablePercent, "recoverablePercent");
      Objects.requireNonNull(validFrom, "validFrom");
    }

    boolean validOn(LocalDate date) {
      return TaxRuleSet.validOn(validFrom, validTo, date);
    }

    TaxApi.TaxRateInfo info() {
      return new TaxApi.TaxRateInfo(code, description, kind, percent, compound, recoverablePercent);
    }
  }

  /** A tax_group_lines row, by codes. */
  public record GroupLine(String group, String rate, int sortOrder) {
  }

  /** A tax_rules row, by codes; {@code group} null means "no tax". */
  public record Rule(String jurisdiction, String category, String group, LocalDate validFrom, LocalDate validTo) {
    public Rule {
      Objects.requireNonNull(jurisdiction, "jurisdiction");
      Objects.requireNonNull(category, "category");
      Objects.requireNonNull(validFrom, "validFrom");
    }
  }

  /** A rate of a group, with its position in the group. */
  private record Member(Rate rate, int sortOrder) {
  }

  private final Set<String> jurisdictions;
  private final Set<String> categories;
  private final Map<String, List<Member>> groups;
  private final List<Rule> rules;

  /** @throws IllegalStateException if a group line or rule names something that isn't there */
  public TaxRuleSet(Collection<String> jurisdictions, Collection<String> categories, Collection<String> groups,
      Collection<Rate> rates, Collection<GroupLine> groupLines, Collection<Rule> rules) {
    this.jurisdictions = Set.copyOf(jurisdictions);
    this.categories = Set.copyOf(categories);
    Map<String, Rate> ratesByCode = new HashMap<>();
    for (Rate r : rates) {
      if (ratesByCode.put(r.code(), r) != null) {
        throw new IllegalStateException("Tax rate " + r.code() + " appears twice");
      }
    }
    Map<String, List<Member>> members = new HashMap<>();
    groups.forEach(g -> members.put(g, new ArrayList<>()));
    for (GroupLine line : groupLines) {
      List<Member> list = members.get(line.group());
      Rate rate = ratesByCode.get(line.rate());
      if (list == null || rate == null) {
        // A disabled group or rate drops out of the data; its lines must too.
        continue;
      }
      list.add(new Member(rate, line.sortOrder()));
    }
    members.replaceAll((g, list) -> List.copyOf(list));
    this.groups = Map.copyOf(members);
    for (Rule rule : rules) {
      if (!this.jurisdictions.contains(rule.jurisdiction()) || !this.categories.contains(rule.category())
          || (rule.group() != null && !this.groups.containsKey(rule.group()))) {
        throw new IllegalStateException("Tax rule " + rule + " names an unknown or disabled jurisdiction, "
            + "category or group");
      }
    }
    this.rules = List.copyOf(rules);
  }

  public boolean hasJurisdiction(String code) {
    return jurisdictions.contains(code);
  }

  public boolean hasCategory(String code) {
    return categories.contains(code);
  }

  public boolean hasGroup(String code) {
    return groups.containsKey(code);
  }

  /**
   * The tax group of a (place of supply, category) on a date; empty for "no
   * tax" (zero-rated, exempt).
   *
   * @throws ConflictException     TAX_RULE_NOT_FOUND if no rule covers the date
   * @throws IllegalStateException if several do
   */
  public Optional<String> groupFor(String placeOfSupply, String category, LocalDate date) {
    List<Rule> matching = rules.stream()
        .filter(r -> r.jurisdiction().equals(placeOfSupply) && r.category().equals(category)
            && validOn(r.validFrom(), r.validTo(), date))
        .toList();
    if (matching.isEmpty()) {
      throw new ConflictException("TAX_RULE_NOT_FOUND", "No tax rule for " + category + " supplies in "
          + placeOfSupply + " on " + date);
    }
    if (matching.size() > 1) {
      throw new IllegalStateException("Several tax rules for " + category + " supplies in " + placeOfSupply + " on "
          + date + ": " + matching);
    }
    return Optional.ofNullable(matching.getFirst().group());
  }

  /**
   * The rates of a group valid on a date, in calculation order (then the
   * group's sort order).
   *
   * @throws ConflictException     TAX_RATE_NOT_FOUND if none is valid then
   * @throws IllegalStateException if two rates of one kind are
   */
  public List<Rate> ratesOf(String group, LocalDate date) {
    List<Member> members = groups.get(group);
    if (members == null) {
      throw new IllegalArgumentException("Unknown tax group " + group);
    }
    List<Rate> valid = members.stream()
        .filter(m -> m.rate().validOn(date))
        .sorted(Comparator.comparingInt((Member m) -> m.rate().calculationOrder()).thenComparingInt(Member::sortOrder))
        .map(Member::rate)
        .toList();
    if (valid.isEmpty()) {
      throw new ConflictException("TAX_RATE_NOT_FOUND", "Tax group " + group + " has no rate valid on " + date);
    }
    Set<TaxKind> kinds = new HashSet<>();
    for (Rate r : valid) {
      if (!kinds.add(r.kind())) {
        throw new IllegalStateException("Tax group " + group + " has several " + r.kind() + " rates valid on " + date
            + ": " + valid.stream().map(Rate::code).toList());
      }
    }
    return valid;
  }

  private static boolean validOn(LocalDate from, LocalDate to, LocalDate date) {
    return !date.isBefore(from) && (to == null || !date.isAfter(to));
  }
}
