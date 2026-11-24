package com.lesconstructionssapete.stempyerpserver.modules.tax;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Random;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.lesconstructionssapete.stempyerpserver.modules.tax.TaxApi.LineTax;
import com.lesconstructionssapete.stempyerpserver.modules.tax.TaxApi.RateTotal;
import com.lesconstructionssapete.stempyerpserver.modules.tax.TaxApi.TaxKind;
import com.lesconstructionssapete.stempyerpserver.modules.tax.TaxApi.TaxLine;
import com.lesconstructionssapete.stempyerpserver.modules.tax.TaxApi.TaxRequest;
import com.lesconstructionssapete.stempyerpserver.modules.tax.TaxApi.TaxResult;
import com.lesconstructionssapete.stempyerpserver.modules.tax.TaxRuleSet.GroupLine;
import com.lesconstructionssapete.stempyerpserver.modules.tax.TaxRuleSet.Rate;
import com.lesconstructionssapete.stempyerpserver.modules.tax.TaxRuleSet.Rule;
import com.lesconstructionssapete.stempyerpserver.platform.error.ConflictException;
import com.lesconstructionssapete.stempyerpserver.platform.error.FieldError;
import com.lesconstructionssapete.stempyerpserver.platform.error.ValidationFailedException;
import com.lesconstructionssapete.stempyerpserver.shared.Money;

/**
 * The calculator on hand-built tax data shaped like V7 (a few provinces), plus
 * a historical compound rate. Expected amounts are worked out in the comments.
 */
class TaxCalculatorTest {

  private static final Currency CAD = Money.currency("CAD");
  private static final LocalDate TODAY = LocalDate.of(2026, 1, 15);
  private static final LocalDate FAR = LocalDate.of(2008, 1, 1);

  private static Rate rate(String code, TaxKind kind, String percent, int order, String recoverable, LocalDate from,
      LocalDate to, boolean compound) {
    return new Rate(code, code + " description", kind, new BigDecimal(percent), compound, new BigDecimal(recoverable),
        order, from, to);
  }

  private static Rate rate(String code, TaxKind kind, String percent, int order, String recoverable, LocalDate from) {
    return rate(code, kind, percent, order, recoverable, from, null, false);
  }

  /** V7's shape, for QC, ON, NS, BC and AB; plus OLD_QC, a compound QST like Quebec's before 2013. */
  static final TaxRuleSet RULES = new TaxRuleSet(
      List.of("CA", "CA-QC", "CA-ON", "CA-NS", "CA-BC", "CA-AB"),
      List.of("STANDARD", "ZERO_RATED", "EXEMPT"),
      List.of("CA_GST", "QC_GST_QST", "ON_HST", "NS_HST", "BC_GST_PST", "OLD_QC"),
      List.of(
          rate("GST_5", TaxKind.GST, "5", 0, "100", FAR),
          rate("QST_9_975", TaxKind.QST, "9.975", 1, "100", LocalDate.of(2013, 1, 1)),
          rate("ON_HST_13", TaxKind.HST, "13", 0, "100", LocalDate.of(2010, 7, 1)),
          rate("NS_HST_15", TaxKind.HST, "15", 0, "100", LocalDate.of(2010, 7, 1), LocalDate.of(2025, 3, 31), false),
          rate("NS_HST_14", TaxKind.HST, "14", 0, "100", LocalDate.of(2025, 4, 1)),
          rate("BC_PST_7", TaxKind.PST, "7", 1, "0", LocalDate.of(2013, 4, 1)),
          rate("GST_6", TaxKind.GST, "6", 0, "100", FAR),
          rate("QST_7_5_C", TaxKind.QST, "7.5", 1, "100", FAR, null, true)),
      List.of(
          new GroupLine("CA_GST", "GST_5", 0),
          new GroupLine("QC_GST_QST", "GST_5", 0), new GroupLine("QC_GST_QST", "QST_9_975", 1),
          new GroupLine("ON_HST", "ON_HST_13", 0),
          new GroupLine("NS_HST", "NS_HST_15", 0), new GroupLine("NS_HST", "NS_HST_14", 0),
          new GroupLine("BC_GST_PST", "GST_5", 0), new GroupLine("BC_GST_PST", "BC_PST_7", 1),
          new GroupLine("OLD_QC", "QST_7_5_C", 1), new GroupLine("OLD_QC", "GST_6", 0)), // sort order on purpose
      List.of(
          new Rule("CA-QC", "STANDARD", "QC_GST_QST", LocalDate.of(2013, 1, 1), null),
          new Rule("CA-ON", "STANDARD", "ON_HST", LocalDate.of(2010, 7, 1), null),
          new Rule("CA-NS", "STANDARD", "NS_HST", LocalDate.of(2010, 7, 1), null),
          new Rule("CA-BC", "STANDARD", "BC_GST_PST", LocalDate.of(2013, 4, 1), null),
          new Rule("CA-AB", "STANDARD", "CA_GST", FAR, null),
          new Rule("CA-QC", "ZERO_RATED", null, FAR, null),
          new Rule("CA-QC", "EXEMPT", null, FAR, null)));

  private final TaxCalculator calculator = new TaxCalculator();

  private static Money cad(String amount) {
    return Money.of(amount, "CAD");
  }

  private static TaxLine line(String key, String amount) {
    return new TaxLine(key, cad(amount), "STANDARD");
  }

  private TaxResult calc(String place, LocalDate date, TaxLine... lines) {
    return calculator.calculate(new TaxRequest(date, place, CAD, List.of(lines)), RULES);
  }

  private static RateTotal total(TaxResult r, String rateCode) {
    return r.rates().stream().filter(t -> t.rate().code().equals(rateCode)).findFirst().orElseThrow();
  }

  private static List<Money> lineTaxes(TaxResult r, String rateCode) {
    return r.lines().stream().flatMap(l -> l.taxes().stream()).filter(t -> t.rate().code().equals(rateCode))
        .map(LineTax::tax).toList();
  }

  // ---- by province ----

  @Test
  void quebecChargesGstAndQst() {
    TaxResult r = calc("CA-QC", TODAY, line("1", "100.00"));
    // GST 5 % of 100.00 = 5.00; QST 9.975 % = 9.975 -> 9.98 (half up)
    assertEquals(List.of("GST_5", "QST_9_975"), r.rates().stream().map(t -> t.rate().code()).toList());
    assertEquals(cad("5.00"), total(r, "GST_5").tax());
    assertEquals(cad("9.98"), total(r, "QST_9_975").tax());
    assertEquals(cad("100.00"), r.taxableTotal());
    assertEquals(cad("14.98"), r.taxTotal());
    assertEquals(cad("114.98"), r.grandTotal());
    var only = r.lines().getFirst();
    assertEquals("1", only.key());
    assertEquals(cad("14.98"), only.taxTotal());
    LineTax qst = only.taxes().get(1);
    assertEquals(new BigDecimal("9.975"), qst.rate().percent());
    assertEquals(TaxKind.QST, qst.rate().kind());
    assertEquals(cad("100.00"), qst.base());
  }

  @Test
  void ontarioChargesHst() {
    TaxResult r = calc("CA-ON", TODAY, line("1", "1234.56"));
    assertEquals(cad("160.49"), r.taxTotal()); // 13 % = 160.4928
    assertEquals(1, r.rates().size());
  }

  @Test
  void albertaChargesGstOnly() {
    assertEquals(cad("61.73"), calc("CA-AB", TODAY, line("1", "1234.56")).taxTotal()); // 5 % = 61.728
  }

  @Test
  void theDocumentDatePicksTheRate() {
    // Nova Scotia went from 15 % to 14 % on 2025-04-01.
    assertEquals(cad("15.00"), calc("CA-NS", LocalDate.of(2025, 3, 31), line("1", "100.00")).taxTotal());
    TaxResult after = calc("CA-NS", LocalDate.of(2025, 4, 1), line("1", "100.00"));
    assertEquals(cad("14.00"), after.taxTotal());
    assertEquals("NS_HST_14", after.rates().getFirst().rate().code());
  }

  // ---- rounding ----

  @Test
  void roundsOncePerRateOnTheDocumentThenSpreadsOverTheLines() {
    // QST on three lines of 10.00: 0.9975 each. Per line it would be 1.00 x 3 = 3.00;
    // on the subtotal 30.00 it is 2.9925 -> 2.99, and the lines must add up to that.
    TaxResult r = calc("CA-QC", TODAY, line("1", "10.00"), line("2", "10.00"), line("3", "10.00"));
    assertEquals(cad("2.99"), total(r, "QST_9_975").tax());
    assertEquals(List.of(cad("0.99"), cad("1.00"), cad("1.00")), lineTaxes(r, "QST_9_975"));
    assertEquals(cad("1.50"), total(r, "GST_5").tax());
    assertEquals(cad("4.49"), r.taxTotal());
    assertEquals(r.taxTotal(), Money.sum(CAD, r.lines().stream().map(l -> l.taxTotal()).toList()));
  }

  @Test
  void aDiscountLineReducesTheTax() {
    // 100.00 and a -30.00 discount: GST 5.00 - 1.50 = 3.50; QST 9.975 - 2.9925 = 6.9825 -> 6.98
    TaxResult r = calc("CA-QC", TODAY, line("work", "100.00"), line("discount", "-30.00"));
    assertEquals(cad("70.00"), r.taxableTotal());
    assertEquals(cad("3.50"), total(r, "GST_5").tax());
    assertEquals(cad("6.98"), total(r, "QST_9_975").tax());
    assertEquals(cad("70.00"), total(r, "QST_9_975").base());
    assertEquals(cad("6.98"), Money.sum(CAD, lineTaxes(r, "QST_9_975")));
  }

  @Test
  void aCreditNoteMirrorsItsInvoice() {
    TaxResult invoice = calc("CA-QC", TODAY, line("1", "10.00"), line("2", "10.00"), line("3", "10.00"));
    TaxResult credit = calc("CA-QC", TODAY, line("1", "-10.00"), line("2", "-10.00"), line("3", "-10.00"));
    assertEquals(invoice.taxTotal().negate(), credit.taxTotal());
    assertEquals(lineTaxes(invoice, "QST_9_975").stream().map(Money::negate).toList(),
        lineTaxes(credit, "QST_9_975"));
  }

  @Test
  void lineTaxesAlwaysAddUpToTheTaxOnTheSubtotal() {
    Random random = new Random(11);
    for (int run = 0; run < 1_000; run++) {
      List<TaxLine> lines = new ArrayList<>();
      int n = 1 + random.nextInt(15);
      for (int i = 0; i < n; i++) {
        lines.add(new TaxLine("L" + i, Money.of(BigDecimal.valueOf(random.nextLong(-50_000, 500_000), 2), CAD),
            "STANDARD"));
      }
      TaxResult r = calculator.calculate(new TaxRequest(TODAY, "CA-QC", CAD, lines), RULES);
      Money subtotal = r.taxableTotal();
      // Each rate: exactly the rate applied to the subtotal, rounded once.
      assertEquals(subtotal.times(new BigDecimal("0.05")).rounded(), total(r, "GST_5").tax());
      assertEquals(subtotal.times(new BigDecimal("0.09975")).rounded(), total(r, "QST_9_975").tax());
      assertEquals(total(r, "QST_9_975").tax(), Money.sum(CAD, lineTaxes(r, "QST_9_975")));
      assertEquals(r.taxTotal(), Money.sum(CAD, r.lines().stream().map(l -> l.taxTotal()).toList()));
    }
  }

  // ---- categories, groups, recoverable, compound ----

  @Test
  void zeroRatedAndExemptLinesCarryNoTax() {
    TaxResult r = calc("CA-QC", TODAY, line("1", "200.00"), new TaxLine("2", cad("50.00"), "ZERO_RATED"),
        new TaxLine("3", cad("25.00"), "EXEMPT"));
    assertEquals(cad("275.00"), r.taxableTotal());
    assertEquals(cad("200.00"), total(r, "GST_5").base());
    assertEquals(cad("10.00"), total(r, "GST_5").tax());
    assertEquals(cad("19.95"), total(r, "QST_9_975").tax());
    assertEquals(List.of(), r.lines().get(1).taxes());
    assertEquals(cad("0"), r.lines().get(2).taxTotal());
  }

  @Test
  void aLineMayNameItsOwnTaxGroup() {
    // A supply in Quebec that only bears GST (e.g. a QST-exempt customer).
    TaxResult r = calc("CA-QC", TODAY, line("1", "100.00"), new TaxLine("2", cad("100.00"), "STANDARD", "CA_GST"));
    assertEquals(cad("10.00"), total(r, "GST_5").tax());
    assertEquals(cad("9.98"), total(r, "QST_9_975").tax());
    assertEquals(1, r.lines().get(1).taxes().size());
  }

  @Test
  void pstIsNotRecoverable() {
    TaxResult r = calc("CA-BC", TODAY, line("1", "100.00"));
    assertEquals(cad("5.00"), total(r, "GST_5").recoverable());
    assertEquals(cad("7.00"), total(r, "BC_PST_7").tax());
    assertEquals(cad("0.00"), total(r, "BC_PST_7").recoverable());
  }

  @Test
  void aCompoundRateIsChargedOnThePriceAndTheEarlierTaxes() {
    // OLD_QC: GST 6 %, then QST 7.5 % on (price + GST).
    // Lines 100.00 and 33.33. GST: 6.00 + 1.9998 = 7.9998 -> 8.00, lines 6.00 and 2.00.
    // QST bases: 106.00 and 35.33 (= 141.33); 7.95 + 2.64975 = 10.59975 -> 10.60, lines 7.95 and 2.65.
    TaxResult r = calculator.calculate(new TaxRequest(TODAY, "CA-QC", CAD, List.of(
        new TaxLine("1", cad("100.00"), "STANDARD", "OLD_QC"),
        new TaxLine("2", cad("33.33"), "STANDARD", "OLD_QC"))), RULES);
    assertEquals(List.of("GST_6", "QST_7_5_C"), r.rates().stream().map(t -> t.rate().code()).toList());
    assertEquals(List.of(cad("6.00"), cad("2.00")), lineTaxes(r, "GST_6"));
    assertEquals(cad("141.33"), total(r, "QST_7_5_C").base());
    assertEquals(List.of(cad("7.95"), cad("2.65")), lineTaxes(r, "QST_7_5_C"));
    assertEquals(cad("18.60"), r.taxTotal());
    assertTrue(total(r, "QST_7_5_C").rate().compound());
  }

  @Test
  void anEmptyDocumentHasNoTax() {
    TaxResult r = calc("CA-QC", TODAY);
    assertEquals(cad("0"), r.taxTotal());
    assertEquals(List.of(), r.rates());
  }

  // ---- errors ----

  @Test
  void reportsEveryBadFieldAtOnce() {
    var e = assertThrows(ValidationFailedException.class, () -> calculator.calculate(new TaxRequest(TODAY, "CA-XX",
        CAD, List.of(
            new TaxLine("1", Money.of("1.00", "USD"), "STANDARD"),
            new TaxLine("1", cad("1.005"), "LUXURY"),
            new TaxLine(" ", cad("1.00"), "STANDARD", "NOPE"))), RULES));
    assertEquals(Set.of("placeOfSupply:UNKNOWN", "lines[0].amount:CURRENCY_MISMATCH", "lines[1].key:DUPLICATE",
        "lines[1].amount:NOT_ROUNDED", "lines[1].category:UNKNOWN", "lines[2].key:REQUIRED",
        "lines[2].taxGroup:UNKNOWN"),
        Set.copyOf(e.fieldErrors().stream().map((FieldError f) -> f.field() + ":" + f.code()).toList()));
  }

  @Test
  void aDateBeforeTheRulesIsAConflict() {
    var e = assertThrows(ConflictException.class, () -> calc("CA-QC", LocalDate.of(2012, 12, 31), line("1", "1.00")));
    assertEquals("TAX_RULE_NOT_FOUND", e.code());
    // No rule for this category in this province at all: the same.
    assertEquals("TAX_RULE_NOT_FOUND", assertThrows(ConflictException.class,
        () -> calc("CA-ON", TODAY, new TaxLine("1", cad("1.00"), "EXEMPT"))).code());
  }

  @Test
  void aGroupWithoutAValidRateIsAConflict() {
    // QC_GST_QST before 2013: GST_5 is valid, QST_9_975 is not yet: still one rate, so fine...
    assertEquals(1, RULES.ratesOf("QC_GST_QST", LocalDate.of(2012, 6, 1)).size());
    // ...but ON_HST before July 2010 has none.
    assertEquals("TAX_RATE_NOT_FOUND", assertThrows(ConflictException.class,
        () -> RULES.ratesOf("ON_HST", LocalDate.of(2010, 6, 30))).code());
  }

  @Test
  void ambiguousDataIsRefused() {
    var overlapping = new TaxRuleSet(List.of("CA-QC"), List.of("STANDARD"), List.of("G"),
        List.of(rate("A", TaxKind.GST, "5", 0, "100", FAR), rate("B", TaxKind.GST, "6", 0, "100", FAR)),
        List.of(new GroupLine("G", "A", 0), new GroupLine("G", "B", 1)),
        List.of(new Rule("CA-QC", "STANDARD", "G", FAR, null),
            new Rule("CA-QC", "STANDARD", null, LocalDate.of(2020, 1, 1), null)));
    assertThrows(IllegalStateException.class, () -> overlapping.groupFor("CA-QC", "STANDARD", TODAY));
    assertThrows(IllegalStateException.class, () -> overlapping.ratesOf("G", TODAY)); // two GST rates
    assertThrows(IllegalStateException.class, () -> new TaxRuleSet(List.of("CA-QC"), List.of("STANDARD"),
        List.of(), List.of(), List.of(), List.of(new Rule("CA-QC", "STANDARD", "MISSING", FAR, null))));
  }
}
