package com.lesconstructionssapete.stempyerpserver.shared;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Currency;
import java.util.List;
import java.util.Objects;

/**
 * An amount of money in one currency. Never use double or float for money.
 *
 * Rules:
 * <ul>
 * <li><b>Exact until you round.</b> plus, minus, times and negate never round,
 * so {@code unitPrice.times(quantity)} keeps every digit. Call
 * {@link #rounded()} when a value becomes a real amount (a line total, a tax,
 * a balance): it rounds to the currency's minor unit (2 decimals for CAD)
 * with {@link #ROUNDING}.</li>
 * <li><b>One currency at a time.</b> Combining or comparing two currencies
 * throws IllegalArgumentException; converting needs an exchange rate, which is
 * business logic, not arithmetic.</li>
 * <li><b>Equal by value.</b> 10.0 CAD equals 10.00 CAD (unlike BigDecimal,
 * whose equals compares the scale too).</li>
 * <li><b>Splitting keeps every cent.</b> {@link #allocate} divides a rounded
 * amount by weights so that the parts add up to it exactly;
 * {@link #roundKeepingTotal} rounds several exact amounts so that they add up
 * to their rounded total.</li>
 * </ul>
 *
 * Persistence (DECIMAL(19,4) + a currency column) and JSON
 * ({"amount": "10.00", "currency": "CAD"}) are handled by the layers above;
 * this class has no dependency.
 *
 * @param amount   any scale; negative for credits and refunds
 * @param currency an ISO 4217 currency with a minor unit (not XXX, XAU, ...)
 */
public record Money(BigDecimal amount, Currency currency) implements Comparable<Money> {

  /**
   * The one rounding rule for money: half a minor unit or more rounds away
   * from zero. It is the rule CRA and Revenu Quebec give for GST/HST/QST
   * ("round to the nearest cent; half a cent or more rounds up").
   */
  public static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

  public Money {
    Objects.requireNonNull(amount, "amount");
    Objects.requireNonNull(currency, "currency");
    if (currency.getDefaultFractionDigits() < 0) {
      throw new IllegalArgumentException("Currency " + currency.getCurrencyCode() + " has no minor unit");
    }
  }

  // ---- creating ----

  public static Money of(BigDecimal amount, Currency currency) {
    return new Money(amount, currency);
  }

  /** {@code Money.of("19.99", "CAD")}. Throws IllegalArgumentException on a bad amount or unknown currency. */
  public static Money of(String amount, String currencyCode) {
    return new Money(new BigDecimal(amount), currency(currencyCode));
  }

  public static Money zero(Currency currency) {
    return new Money(BigDecimal.ZERO, currency);
  }

  /** The ISO 4217 currency for a code like "CAD"; IllegalArgumentException if unknown. */
  public static Currency currency(String code) {
    Objects.requireNonNull(code, "currency code");
    return Currency.getInstance(code); // throws IllegalArgumentException for an unknown code
  }

  /** The exact sum; zero in {@code currency} when there is nothing to add. */
  public static Money sum(Currency currency, Collection<Money> amounts) {
    Money total = zero(currency);
    for (Money m : amounts) {
      total = total.plus(m);
    }
    return total;
  }

  // ---- arithmetic (exact) ----

  public Money plus(Money other) {
    requireSameCurrency(other);
    return new Money(amount.add(other.amount), currency);
  }

  public Money minus(Money other) {
    requireSameCurrency(other);
    return new Money(amount.subtract(other.amount), currency);
  }

  /** Exact product, e.g. unit price x quantity, or base x rate. Round the result when it becomes an amount. */
  public Money times(BigDecimal factor) {
    Objects.requireNonNull(factor, "factor");
    return new Money(amount.multiply(factor), currency);
  }

  public Money times(long factor) {
    return times(BigDecimal.valueOf(factor));
  }

  public Money negate() {
    return new Money(amount.negate(), currency);
  }

  public Money abs() {
    return amount.signum() < 0 ? negate() : this;
  }

  // ---- rounding ----

  /** Decimal places of the currency's minor unit: 2 for CAD and USD, 0 for JPY. */
  public int minorUnitDigits() {
    return currency.getDefaultFractionDigits();
  }

  /** Rounded to the currency's minor unit with {@link #ROUNDING}. */
  public Money rounded() {
    return rounded(ROUNDING);
  }

  /** Rounded to the currency's minor unit with another mode (e.g. DOWN for a discount that must not overshoot). */
  public Money rounded(RoundingMode mode) {
    return new Money(amount.setScale(minorUnitDigits(), mode), currency);
  }

  /** True if rounding would not change the value (10.5000 CAD is rounded; 10.005 CAD is not). */
  public boolean isRounded() {
    return amount.stripTrailingZeros().scale() <= minorUnitDigits();
  }

  // ---- splitting ----

  /**
   * Splits this amount in proportion to {@code weights}, in minor units, so
   * that the parts add up to it exactly. Each part gets its rounded-down share;
   * the units left over go one by one to the parts with the largest remainders
   * (ties: the earlier part). A part with weight 0 gets 0.
   *
   * Typical uses: a document-level tax spread back over its lines, a payment
   * split over instalments, a cost spread over jobs.
   *
   * @param weights at least one, none negative, not all zero; any scale
   * @throws IllegalArgumentException if this amount is not {@link #isRounded()
   *                                  rounded} (round it first, deliberately), or
   *                                  the weights are unusable
   */
  public List<Money> allocate(List<BigDecimal> weights) {
    Objects.requireNonNull(weights, "weights");
    if (weights.isEmpty()) {
      throw new IllegalArgumentException("allocate needs at least one weight");
    }
    BigDecimal totalWeight = BigDecimal.ZERO;
    for (BigDecimal w : weights) {
      Objects.requireNonNull(w, "weight");
      if (w.signum() < 0) {
        throw new IllegalArgumentException("allocate weights must not be negative: " + w);
      }
      totalWeight = totalWeight.add(w);
    }
    if (totalWeight.signum() == 0) {
      throw new IllegalArgumentException("allocate weights must not all be zero");
    }
    if (!isRounded()) {
      throw new IllegalArgumentException("Round " + this + " before allocating it");
    }

    // Work in whole minor units of the absolute amount; the sign is put back at the end.
    int digits = minorUnitDigits();
    BigDecimal units = amount.abs().movePointRight(digits).setScale(0, RoundingMode.UNNECESSARY);
    int n = weights.size();
    BigDecimal[] shares = new BigDecimal[n];
    BigDecimal[] remainders = new BigDecimal[n];
    BigDecimal given = BigDecimal.ZERO;
    for (int i = 0; i < n; i++) {
      // units * w / total, split into whole units and what is left (exact: no division rounding)
      BigDecimal[] qr = units.multiply(weights.get(i)).divideAndRemainder(totalWeight);
      shares[i] = qr[0].setScale(0, RoundingMode.UNNECESSARY);
      remainders[i] = qr[1];
      given = given.add(shares[i]);
    }
    int leftover = units.subtract(given).intValueExact(); // < n
    Integer[] order = new Integer[n];
    Arrays.setAll(order, i -> i);
    Arrays.sort(order, Comparator.<Integer, BigDecimal>comparing(i -> remainders[i]).reversed()
        .thenComparing(i -> i));
    for (int k = 0; k < leftover; k++) {
      shares[order[k]] = shares[order[k]].add(BigDecimal.ONE);
    }

    List<Money> parts = new ArrayList<>(n);
    for (BigDecimal share : shares) {
      BigDecimal part = share.movePointLeft(digits).setScale(digits, RoundingMode.UNNECESSARY);
      parts.add(new Money(amount.signum() < 0 ? part.negate() : part, currency));
    }
    return List.copyOf(parts);
  }

  /** Splits into {@code parts} equal parts (the first ones get the extra minor units). */
  public List<Money> allocate(int parts) {
    if (parts < 1) {
      throw new IllegalArgumentException("parts must be at least 1");
    }
    return allocate(Collections.nCopies(parts, BigDecimal.ONE));
  }

  /**
   * Rounds several exact amounts together: the parts add up to the rounded
   * total of the exact amounts, and each part is within one minor unit of its
   * exact value. Each part is first rounded normally ({@link #ROUNDING}); if
   * they then miss the rounded total, the units are added to (or taken from)
   * the parts that were rounded the furthest the other way, one each (ties:
   * the earlier part). Works with any signs, and is symmetric: negating every
   * input negates every output, so a credit note mirrors its invoice line by
   * line.
   *
   * This is document-level rounding spread back to the lines: for line taxes
   * {@code base_i x rate}, the total is {@code round(sum(base_i) x rate)}, the
   * tax on the subtotal, and the parts are the line taxes that add up to it.
   *
   * @param exact any scale, one currency; an empty list gives an empty list
   */
  public static List<Money> roundKeepingTotal(List<Money> exact) {
    Objects.requireNonNull(exact, "exact");
    if (exact.isEmpty()) {
      return List.of();
    }
    Currency currency = exact.getFirst().currency;
    Money total = sum(currency, exact).rounded();
    int n = exact.size();
    Money[] parts = new Money[n];
    BigDecimal[] error = new BigDecimal[n]; // exact - rounded: > 0 rounded down, < 0 rounded up
    Money rounded = zero(currency);
    for (int i = 0; i < n; i++) {
      parts[i] = exact.get(i).rounded();
      error[i] = exact.get(i).amount.subtract(parts[i].amount);
      rounded = rounded.plus(parts[i]);
    }
    BigDecimal unit = BigDecimal.ONE.movePointLeft(exact.getFirst().minorUnitDigits());
    int missing = total.amount.subtract(rounded.amount).divide(unit).intValueExact(); // |missing| <= n / 2
    Integer[] order = new Integer[n];
    Arrays.setAll(order, i -> i);
    // Short of the total: add to the parts rounded down the most. Over it: take from those rounded up the most.
    Comparator<Integer> byError = Comparator.comparing(i -> error[i]);
    Arrays.sort(order, (missing > 0 ? byError.reversed() : byError).thenComparing(i -> i));
    Money step = new Money(missing > 0 ? unit : unit.negate(), currency);
    for (int k = 0; k < Math.abs(missing); k++) {
      parts[order[k]] = parts[order[k]].plus(step);
    }
    return List.of(parts);
  }

  // ---- comparing ----

  public int signum() {
    return amount.signum();
  }

  public boolean isZero() {
    return amount.signum() == 0;
  }

  public boolean isPositive() {
    return amount.signum() > 0;
  }

  public boolean isNegative() {
    return amount.signum() < 0;
  }

  /** By value; IllegalArgumentException for another currency. */
  @Override
  public int compareTo(Money other) {
    requireSameCurrency(other);
    return amount.compareTo(other.amount);
  }

  public boolean isGreaterThan(Money other) {
    return compareTo(other) > 0;
  }

  public boolean isLessThan(Money other) {
    return compareTo(other) < 0;
  }

  /** Same currency and same value, whatever the scale: 10.0 CAD equals 10.00 CAD. */
  @Override
  public boolean equals(Object o) {
    return o instanceof Money m && currency.equals(m.currency) && amount.compareTo(m.amount) == 0;
  }

  @Override
  public int hashCode() {
    return 31 * currency.hashCode() + amount.stripTrailingZeros().hashCode();
  }

  /** "19.99 CAD", the amount as stored (no rounding, no exponent). For logs and messages, not for display. */
  @Override
  public String toString() {
    return amount.toPlainString() + " " + currency.getCurrencyCode();
  }

  private void requireSameCurrency(Money other) {
    Objects.requireNonNull(other, "other");
    if (!currency.equals(other.currency)) {
      throw new IllegalArgumentException("Cannot combine " + this + " and " + other + ": different currencies");
    }
  }
}
