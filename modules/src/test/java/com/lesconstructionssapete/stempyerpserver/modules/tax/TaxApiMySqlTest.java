package com.lesconstructionssapete.stempyerpserver.modules.tax;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.lesconstructionssapete.stempyerpserver.modules.tax.TaxApi.LineTax;
import com.lesconstructionssapete.stempyerpserver.modules.tax.TaxApi.RateTotal;
import com.lesconstructionssapete.stempyerpserver.modules.tax.TaxApi.TaxKind;
import com.lesconstructionssapete.stempyerpserver.modules.tax.TaxApi.TaxLine;
import com.lesconstructionssapete.stempyerpserver.modules.tax.TaxApi.TaxRequest;
import com.lesconstructionssapete.stempyerpserver.modules.tax.TaxApi.TaxResult;
import com.lesconstructionssapete.stempyerpserver.modules.testsupport.MySqlTestStack;
import com.lesconstructionssapete.stempyerpserver.modules.users.AuthSettings;
import com.lesconstructionssapete.stempyerpserver.platform.db.Jdbc;
import com.lesconstructionssapete.stempyerpserver.platform.db.UnitOfWorkFactory;
import com.lesconstructionssapete.stempyerpserver.platform.error.ConflictException;
import com.lesconstructionssapete.stempyerpserver.shared.Money;

/**
 * TaxApi as other modules get it (from the ServiceRegistry), on the rates and
 * rules V7 seeds: one document per province, the Nova Scotia rate change,
 * zero-rated and exempt supplies, and disabled rows.
 */
class TaxApiMySqlTest {

  private static final LocalDate TODAY = LocalDate.of(2026, 1, 15);

  private static MySqlTestStack stack;
  private static UnitOfWorkFactory uow;
  private static TaxApi taxes;

  @BeforeAll
  @SuppressWarnings("unused")
  static void startStack() throws Exception {
    stack = MySqlTestStack.start(TaxApiMySqlTest.class, AuthSettings.defaults(), st -> {
      // the seeded tax data is all this test needs
    });
    uow = new UnitOfWorkFactory(stack.dataSource());
    taxes = stack.services().get(TaxApi.class);
  }

  @AfterAll
  @SuppressWarnings("unused")
  static void stopStack() {
    if (stack != null) {
      stack.close();
    }
  }

  @AfterEach
  @SuppressWarnings("unused")
  void enableEverything() {
    uow.run(u -> {
      Jdbc.update(u, "UPDATE tax_rates SET is_enabled = TRUE");
      Jdbc.update(u, "UPDATE tax_groups SET is_enabled = TRUE");
    });
  }

  private static Money cad(String amount) {
    return Money.of(amount, "CAD");
  }

  private static TaxResult calc(String place, LocalDate date, String category, String amount) {
    // In a transaction of its own here; a document would pass the one that saves it.
    return uow.execute(u -> taxes.calculate(u, new TaxRequest(date, place, Money.currency("CAD"),
        List.of(new TaxLine("1", cad(amount), category)))));
  }

  @ParameterizedTest(name = "{0}: {1}")
  @CsvSource({
      "CA-AB, 5.00, GST_5",
      "CA-BC, 12.00, GST_5 BC_PST_7",
      "CA-MB, 12.00, GST_5 MB_RST_7",
      "CA-NB, 15.00, NB_HST_15",
      "CA-NL, 15.00, NL_HST_15",
      "CA-NS, 14.00, NS_HST_14",
      "CA-NT, 5.00, GST_5",
      "CA-NU, 5.00, GST_5",
      "CA-ON, 13.00, ON_HST_13",
      "CA-PE, 15.00, PE_HST_15",
      "CA-QC, 14.98, GST_5 QST_9_975",
      "CA-SK, 11.00, GST_5 SK_PST_6",
      "CA-YT, 5.00, GST_5" })
  void everyProvinceAndTerritoryTaxesAStandardSupply(String place, String tax, String rates) {
    TaxResult r = calc(place, TODAY, "STANDARD", "100.00");
    assertEquals(cad(tax), r.taxTotal());
    assertEquals(List.of(rates.split(" ")), r.rates().stream().map(t -> t.rate().code()).toList());
  }

  @Test
  void quebecSnapshotCarriesTheRateAsApplied() {
    TaxResult r = calc("CA-QC", TODAY, "STANDARD", "100.00");
    LineTax qst = r.lines().getFirst().taxes().get(1);
    assertEquals("QST_9_975", qst.rate().code());
    assertEquals("Quebec Sales Tax 9.975 %", qst.rate().description());
    assertEquals(TaxKind.QST, qst.rate().kind());
    assertEquals(0, new BigDecimal("9.975").compareTo(qst.rate().percent()));
    assertEquals(cad("9.98"), qst.tax());
    assertEquals(cad("9.98"), qst.recoverable());
  }

  @Test
  void pstIsNotRecoverable() {
    RateTotal pst = calc("CA-BC", TODAY, "STANDARD", "100.00").rates().get(1);
    assertEquals(cad("7.00"), pst.tax());
    assertEquals(cad("0.00"), pst.recoverable());
  }

  @Test
  void novaScotiaChangedRateOnAprilFirst2025() {
    assertEquals(cad("15.00"), calc("CA-NS", LocalDate.of(2025, 3, 31), "STANDARD", "100.00").taxTotal());
    assertEquals(cad("14.00"), calc("CA-NS", LocalDate.of(2025, 4, 1), "STANDARD", "100.00").taxTotal());
  }

  @ParameterizedTest
  @CsvSource({ "CA-QC, ZERO_RATED", "CA-QC, EXEMPT", "CA-ON, ZERO_RATED", "CA-BC, EXEMPT" })
  void zeroRatedAndExemptSuppliesBearNoTax(String place, String category) {
    TaxResult r = calc(place, TODAY, category, "100.00");
    assertEquals(cad("0"), r.taxTotal());
    assertEquals(List.of(), r.rates());
  }

  @Test
  void datesBeforeTheSeededRatesHaveNoRule() {
    var e = assertThrows(ConflictException.class, () -> calc("CA-QC", LocalDate.of(2012, 12, 31), "STANDARD", "1.00"));
    assertEquals("TAX_RULE_NOT_FOUND", e.code());
  }

  @Test
  void disabledRowsAreLeftOut() {
    // A disabled rate drops out of its groups: Quebec then charges GST only.
    uow.run(u -> Jdbc.update(u, "UPDATE tax_rates SET is_enabled = FALSE WHERE code = 'QST_9_975'"));
    assertEquals(cad("5.00"), calc("CA-QC", TODAY, "STANDARD", "100.00").taxTotal());

    // A disabled group takes its rules with it: no rule, not a rule pointing nowhere.
    uow.run(u -> Jdbc.update(u, "UPDATE tax_groups SET is_enabled = FALSE WHERE code = 'ON_HST'"));
    assertEquals("TAX_RULE_NOT_FOUND", assertThrows(ConflictException.class,
        () -> calc("CA-ON", TODAY, "STANDARD", "100.00")).code());
  }
}
