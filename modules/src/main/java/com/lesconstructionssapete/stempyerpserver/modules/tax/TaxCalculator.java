package com.lesconstructionssapete.stempyerpserver.modules.tax;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.lesconstructionssapete.stempyerpserver.modules.tax.TaxApi.LineTax;
import com.lesconstructionssapete.stempyerpserver.modules.tax.TaxApi.LineTaxes;
import com.lesconstructionssapete.stempyerpserver.modules.tax.TaxApi.RateTotal;
import com.lesconstructionssapete.stempyerpserver.modules.tax.TaxApi.TaxLine;
import com.lesconstructionssapete.stempyerpserver.modules.tax.TaxApi.TaxRequest;
import com.lesconstructionssapete.stempyerpserver.modules.tax.TaxApi.TaxResult;
import com.lesconstructionssapete.stempyerpserver.modules.tax.TaxRuleSet.Rate;
import com.lesconstructionssapete.stempyerpserver.platform.error.Validation;
import com.lesconstructionssapete.stempyerpserver.shared.Money;

/**
 * The taxes of a document, from a request and the tax data. Pure: no
 * database, no clock, no state; the same input always gives the same result.
 *
 * For each line, the place of supply, the line's category and the date pick a
 * tax group (or the line's own group, or none), and the group's rates valid
 * that day apply. Then, rate by rate in calculation order:
 * <ol>
 * <li>each line's base is its amount, plus its earlier taxes for a compound
 * rate;</li>
 * <li>each line's exact tax is base x percent / 100;</li>
 * <li>the exact taxes are rounded together ({@link Money#roundKeepingTotal}):
 * the rate's total is the tax on the document's base, rounded once, and the
 * line taxes add up to it exactly;</li>
 * <li>the recoverable part (ITC / ITR) is the line tax x recoverable percent,
 * rounded the same way.</li>
 * </ol>
 */
public final class TaxCalculator {

  public TaxResult calculate(TaxRequest request, TaxRuleSet rules) {
    validate(request, rules);
    List<TaxLine> lines = request.lines();
    int n = lines.size();

    // Which rates apply to each line, and every rate used, in calculation order.
    List<List<Rate>> ratesOfLine = new ArrayList<>(n);
    Map<String, Rate> used = new LinkedHashMap<>();
    for (TaxLine line : lines) {
      String group = line.taxGroup() != null ? line.taxGroup()
          : rules.groupFor(request.placeOfSupply(), line.category(), request.date()).orElse(null);
      List<Rate> rates = group == null ? List.of() : rules.ratesOf(group, request.date());
      ratesOfLine.add(rates);
      rates.forEach(r -> used.putIfAbsent(r.code(), r));
    }
    List<Rate> order = new ArrayList<>(used.values());
    order.sort(Comparator.comparingInt(Rate::calculationOrder)); // stable: ties keep first-use order

    // Line by line, rate by rate: the taxes computed so far.
    List<Map<String, LineTax>> taxesOfLine = new ArrayList<>(n);
    for (int i = 0; i < n; i++) {
      taxesOfLine.add(new LinkedHashMap<>());
    }
    List<RateTotal> totals = new ArrayList<>();
    for (Rate rate : order) {
      List<Integer> applies = new ArrayList<>();
      List<Money> bases = new ArrayList<>();
      List<Money> exactTaxes = new ArrayList<>();
      for (int i = 0; i < n; i++) {
        if (!ratesOfLine.get(i).contains(rate)) {
          continue;
        }
        Money base = lines.get(i).amount();
        if (rate.compound()) {
          // Tax on tax: the line's taxes from rates calculated before this one.
          for (LineTax earlier : taxesOfLine.get(i).values()) {
            if (used.get(earlier.rate().code()).calculationOrder() < rate.calculationOrder()) {
              base = base.plus(earlier.tax());
            }
          }
        }
        applies.add(i);
        bases.add(base);
        exactTaxes.add(base.times(percent(rate.percent())));
      }
      List<Money> taxes = Money.roundKeepingTotal(exactTaxes);
      List<Money> recoverables = Money.roundKeepingTotal(
          taxes.stream().map(t -> t.times(percent(rate.recoverablePercent()))).toList());

      var info = rate.info();
      for (int k = 0; k < applies.size(); k++) {
        taxesOfLine.get(applies.get(k)).put(rate.code(),
            new LineTax(info, bases.get(k), taxes.get(k), recoverables.get(k)));
      }
      totals.add(new RateTotal(info, Money.sum(request.currency(), bases), Money.sum(request.currency(), taxes),
          Money.sum(request.currency(), recoverables)));
    }

    List<LineTaxes> lineResults = new ArrayList<>(n);
    for (int i = 0; i < n; i++) {
      lineResults.add(new LineTaxes(lines.get(i).key(), lines.get(i).amount(),
          List.copyOf(taxesOfLine.get(i).values())));
    }
    Money taxable = Money.sum(request.currency(), lines.stream().map(TaxLine::amount).toList());
    Money tax = Money.sum(request.currency(), totals.stream().map(RateTotal::tax).toList());
    return new TaxResult(taxable, tax, totals, lineResults);
  }

  /** 9.975 -> 0.09975, exactly. */
  private static BigDecimal percent(BigDecimal percent) {
    return percent.movePointLeft(2);
  }

  private static void validate(TaxRequest request, TaxRuleSet rules) {
    Validation v = Validation.check();
    if (!rules.hasJurisdiction(request.placeOfSupply())) {
      v.add("placeOfSupply", "UNKNOWN", "Unknown tax jurisdiction " + request.placeOfSupply());
    }
    Set<String> keys = new HashSet<>();
    for (int i = 0; i < request.lines().size(); i++) {
      TaxLine line = request.lines().get(i);
      String at = "lines[" + i + "].";
      if (line.key().isBlank()) {
        v.add(at + "key", "REQUIRED", "A line key is required");
      } else if (!keys.add(line.key())) {
        v.add(at + "key", "DUPLICATE", "Line key " + line.key() + " is used twice");
      }
      if (!line.amount().currency().equals(request.currency())) {
        v.add(at + "amount", "CURRENCY_MISMATCH", "The line is in " + line.amount().currency()
            + ", the document in " + request.currency());
      } else if (!line.amount().isRounded()) {
        v.add(at + "amount", "NOT_ROUNDED", "Round the line amount to the currency first: " + line.amount());
      }
      if (!rules.hasCategory(line.category())) {
        v.add(at + "category", "UNKNOWN", "Unknown tax category " + line.category());
      }
      if (line.taxGroup() != null && !rules.hasGroup(line.taxGroup())) {
        v.add(at + "taxGroup", "UNKNOWN", "Unknown tax group " + line.taxGroup());
      }
    }
    v.throwIfInvalid();
  }
}
