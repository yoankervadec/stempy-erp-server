package com.lesconstructionssapete.stempyerpserver.shared;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class MoneyTest {

  private static final Currency CAD = Money.currency("CAD");
  private static final Currency USD = Money.currency("USD");

  private static Money cad(String amount) {
    return Money.of(amount, "CAD");
  }

  private static List<Money> cad(String... amounts) {
    return List.of(amounts).stream().map(MoneyTest::cad).toList();
  }

  private static List<BigDecimal> weights(String... values) {
    return List.of(values).stream().map(BigDecimal::new).toList();
  }

  // ---- creating ----

  @Test
  void createsFromStringsAndRejectsBadInput() {
    assertEquals(new Money(new BigDecimal("19.99"), CAD), cad("19.99"));
    assertEquals("19.99 CAD", cad("19.99").toString());
    assertEquals("0.0000001 CAD", cad("1E-7").toString()); // never an exponent
    assertThrows(IllegalArgumentException.class, () -> Money.of("19.99", "XYZ"));
    assertThrows(NumberFormatException.class, () -> Money.of("19,99", "CAD"));
    assertThrows(IllegalArgumentException.class, () -> Money.of("1", "XXX")); // no minor unit
    assertThrows(NullPointerException.class, () -> new Money(null, CAD));
  }

  @Test
  void sumsInOneCurrency() {
    assertEquals(cad("0"), Money.sum(CAD, List.of()));
    assertEquals(cad("6.06"), Money.sum(CAD, cad("1.01", "2.02", "3.03")));
    assertThrows(IllegalArgumentException.class, () -> Money.sum(CAD, List.of(cad("1"), Money.of("1", "USD"))));
  }

  // ---- arithmetic ----

  @Test
  void arithmeticIsExact() {
    assertEquals(cad("3.50"), cad("1.25").plus(cad("2.25")));
    assertEquals(cad("-1.00"), cad("1.25").minus(cad("2.25")));
    // 3 x 0.3333 stays 0.9999: nothing rounds behind your back
    assertEquals(new BigDecimal("0.9999"), cad("0.3333").times(3).amount());
    assertEquals(new BigDecimal("12.345678"), cad("123.45678").times(new BigDecimal("0.1")).amount());
    assertEquals(cad("-5"), cad("5").negate());
    assertEquals(cad("5"), cad("-5").abs());
  }

  @Test
  void refusesToMixCurrencies() {
    Money usd = Money.of("1", "USD");
    assertThrows(IllegalArgumentException.class, () -> cad("1").plus(usd));
    assertThrows(IllegalArgumentException.class, () -> cad("1").minus(usd));
    assertThrows(IllegalArgumentException.class, () -> cad("1").compareTo(usd));
  }

  // ---- rounding ----

  @ParameterizedTest
  @CsvSource({
      "0.004, 0.00",
      "0.005, 0.01",   // half a cent rounds up (CRA / Revenu Quebec)
      "0.025, 0.03",   // HALF_UP, not banker's rounding (HALF_EVEN would give 0.02)
      "2.675, 2.68",
      "-0.005, -0.01", // away from zero for credits too, so a credit note mirrors its invoice
      "10.4999, 10.50",
      "7, 7.00" })
  void roundsHalfUpToTheCent(String raw, String expected) {
    Money r = cad(raw).rounded();
    assertEquals(new BigDecimal(expected), r.amount()); // scale 2 exactly
  }

  @Test
  void roundsToTheCurrencyMinorUnit() {
    assertEquals(new BigDecimal("1235"), Money.of("1234.5", "JPY").rounded().amount());
    assertEquals(new BigDecimal("1.235"), Money.of("1.2345", "BHD").rounded().amount()); // 3 decimals
    assertEquals(new BigDecimal("0.99"), cad("0.999").rounded(RoundingMode.DOWN).amount());
  }

  @Test
  void knowsWhetherItIsRounded() {
    assertTrue(cad("10").isRounded());
    assertTrue(cad("10.5000").isRounded());
    assertFalse(cad("10.005").isRounded());
  }

  // ---- comparing ----

  @Test
  void equalityIgnoresScaleButNotCurrency() {
    assertEquals(cad("10.0"), cad("10.00"));
    assertEquals(cad("10.0").hashCode(), cad("10.00").hashCode());
    assertEquals(cad("0").hashCode(), cad("0.000").hashCode());
    assertNotEquals(cad("10"), Money.of("10", "USD"));
    assertNotEquals(cad("10"), cad("10.01"));
  }

  @Test
  void comparesAndReportsSign() {
    assertTrue(cad("2").isGreaterThan(cad("1.99")));
    assertTrue(cad("-1").isLessThan(cad("0")));
    assertEquals(0, cad("1.0").compareTo(cad("1.000")));
    assertTrue(cad("0.00").isZero());
    assertTrue(cad("0.01").isPositive());
    assertTrue(cad("-0.01").isNegative());
    assertEquals(-1, cad("-3").signum());
  }

  // ---- allocate ----

  @Test
  void splitsEvenlyGivingExtraCentsToTheFirstParts() {
    assertEquals(cad("33.34", "33.33", "33.33"), cad("100.00").allocate(3));
    assertEquals(cad("0.01", "0.00", "0.00"), cad("0.01").allocate(3));
    assertEquals(List.of(cad("5.00")), cad("5").allocate(1));
  }

  @Test
  void splitsByWeightsWithLargestRemainders() {
    // A document tax of 14.98 spread over lines of 50.00, 30.00 and 20.00:
    // exact shares 7.49, 4.494, 2.996 -> floors 7.49, 4.49, 2.99 (1 cent left) -> to the largest remainder (2.996)
    assertEquals(cad("7.49", "4.49", "3.00"), cad("14.98").allocate(weights("50.00", "30.00", "20.00")));
    // weights of any scale, and a zero weight gets nothing
    assertEquals(cad("10.00", "0.00", "5.00"), cad("15.00").allocate(weights("2", "0", "1.0")));
  }

  @Test
  void splitsNegativeAmountsAsTheMirrorOfPositiveOnes() {
    assertEquals(cad("-33.34", "-33.33", "-33.33"), cad("-100.00").allocate(3));
    List<Money> credit = cad("-14.98").allocate(weights("50", "30", "20"));
    assertEquals(cad("14.98").allocate(weights("50", "30", "20")).stream().map(Money::negate).toList(), credit);
  }

  @Test
  void zeroSplitsIntoZeros() {
    assertEquals(cad("0.00", "0.00"), cad("0").allocate(weights("1", "3")));
  }

  @Test
  void refusesUnusableInput() {
    assertThrows(IllegalArgumentException.class, () -> cad("10.005").allocate(2)); // round first
    assertThrows(IllegalArgumentException.class, () -> cad("10").allocate(0));
    assertThrows(IllegalArgumentException.class, () -> cad("10").allocate(List.of()));
    assertThrows(IllegalArgumentException.class, () -> cad("10").allocate(weights("0", "0")));
    assertThrows(IllegalArgumentException.class, () -> cad("10").allocate(weights("1", "-1")));
  }

  @Test
  void partsAlwaysAddUpExactly() {
    Random random = new Random(42); // fixed seed: reproducible
    for (int run = 0; run < 2_000; run++) {
      Money total = Money.of(BigDecimal.valueOf(random.nextLong(-1_000_000, 1_000_000), 2), CAD);
      int n = 1 + random.nextInt(12);
      List<BigDecimal> w = new ArrayList<>();
      for (int i = 0; i < n; i++) {
        w.add(BigDecimal.valueOf(random.nextInt(0, 100_000), random.nextInt(0, 4)));
      }
      if (w.stream().allMatch(x -> x.signum() == 0)) {
        w.set(0, BigDecimal.ONE);
      }
      List<Money> parts = total.allocate(w);
      assertEquals(total, Money.sum(CAD, parts), () -> total + " by " + w + " -> " + parts);
      for (int i = 0; i < n; i++) {
        Money part = parts.get(i);
        assertTrue(part.isRounded());
        assertTrue(part.isZero() || part.signum() == total.signum(), "no part has the wrong sign");
        if (w.get(i).signum() == 0) {
          assertTrue(part.isZero(), "zero weight, zero part");
        }
        // each part is within one cent of its exact share
        BigDecimal exact = total.amount().multiply(w.get(i))
            .divide(w.stream().reduce(BigDecimal.ZERO, BigDecimal::add), 10, RoundingMode.HALF_UP);
        assertTrue(part.amount().subtract(exact).abs().compareTo(new BigDecimal("0.01")) < 0,
            () -> part + " vs exact " + exact);
      }
    }
  }

  @Test
  void usdWorksTheSameWay() {
    assertEquals(Money.of("33.34", "USD"), Money.of("100", "USD").allocate(3).getFirst());
    assertEquals(USD, Money.zero(USD).currency());
  }
}
