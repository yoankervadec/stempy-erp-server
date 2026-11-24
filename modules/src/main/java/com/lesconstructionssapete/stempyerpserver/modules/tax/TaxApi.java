package com.lesconstructionssapete.stempyerpserver.modules.tax;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Currency;
import java.util.List;
import java.util.Objects;

import com.lesconstructionssapete.stempyerpserver.platform.db.UnitOfWork;
import com.lesconstructionssapete.stempyerpserver.shared.Money;

/**
 * Public API of the tax module, published in the ServiceRegistry: the sales
 * taxes of a document (docs/ARCHITECTURE.md §19).
 *
 * How taxes are found: each line's category (STANDARD, ZERO_RATED, EXEMPT...)
 * and the document's place of supply (CA-QC, CA-ON...) select a tax rule
 * valid on the document date, which names a tax group (or "no tax"); the
 * group's rates valid on that date apply.
 *
 * How they are rounded: per rate, once, on the document (the tax on the
 * subtotal), then spread back over the lines so the line taxes add up to it
 * exactly. Store the result as the document's tax snapshot; never recompute
 * the taxes of an issued document.
 *
 * Prices are always before tax (tax-included prices are not supported).
 */
public interface TaxApi {

  /**
   * The taxes of a document, read with the caller's transaction.
   *
   * @throws com.lesconstructionssapete.stempyerpserver.platform.error.ValidationFailedException
   *         a bad request: unknown place / category / group, a currency
   *         that differs from the request's, an unrounded amount, a
   *         duplicate line key
   * @throws com.lesconstructionssapete.stempyerpserver.platform.error.ConflictException
   *         TAX_RULE_NOT_FOUND or TAX_RATE_NOT_FOUND: the tax data does not
   *         cover this place, category and date
   */
  TaxResult calculate(UnitOfWork uow, TaxRequest request);

  // ---- request ----

  /**
   * @param date          the document date: selects the rules and rates
   * @param placeOfSupply a jurisdiction code, e.g. "CA-QC" (where the goods
   *                      are delivered or the work is done)
   * @param currency      the document currency; every line must use it
   * @param lines         in document order; may be empty
   */
  record TaxRequest(LocalDate date, String placeOfSupply, Currency currency, List<TaxLine> lines) {
    public TaxRequest {
      Objects.requireNonNull(date, "date");
      Objects.requireNonNull(placeOfSupply, "placeOfSupply");
      Objects.requireNonNull(currency, "currency");
      lines = List.copyOf(Objects.requireNonNull(lines, "lines"));
    }
  }

  /**
   * @param key       the caller's id for the line (e.g. its line number),
   *                  unique in the request; echoed in the result
   * @param amount    the line's taxable amount before tax, rounded to the
   *                  currency (quantity x price - discount, rounded by the
   *                  caller); negative for credits and discount lines
   * @param category  tax category code, e.g. "STANDARD"
   * @param taxGroup  optional: a tax group code that replaces the rule's
   *                  choice (e.g. a customer with an exemption, a supply
   *                  taxed elsewhere); null to follow the rules
   */
  record TaxLine(String key, Money amount, String category, String taxGroup) {
    public TaxLine {
      Objects.requireNonNull(key, "key");
      Objects.requireNonNull(amount, "amount");
      Objects.requireNonNull(category, "category");
    }

    public TaxLine(String key, Money amount, String category) {
      this(key, amount, category, null);
    }
  }

  // ---- result ----

  /** Which return a tax goes on: GST and HST to CRA, QST to Revenu Quebec, PST to the province. */
  enum TaxKind {
    GST, HST, QST, PST
  }

  /**
   * @param taxableTotal sum of the line amounts
   * @param taxTotal     sum of the rate totals' taxes
   * @param rates        one per rate used, in calculation order: what the
   *                     document prints and what returns report
   * @param lines        one per request line, in request order
   */
  record TaxResult(Money taxableTotal, Money taxTotal, List<RateTotal> rates, List<LineTaxes> lines) {
    public TaxResult {
      rates = List.copyOf(rates);
      lines = List.copyOf(lines);
    }

    /** Taxable total + tax total: what the customer pays. */
    public Money grandTotal() {
      return taxableTotal.plus(taxTotal);
    }
  }

  /**
   * One rate on the whole document.
   *
   * @param base        what the rate was applied to (for a compound rate,
   *                    including the earlier taxes)
   * @param tax         the rate applied to the base, rounded once
   * @param recoverable the part a registered buyer can claim back (ITC /
   *                    ITR): tax x recoverable percent, rounded
   */
  record RateTotal(TaxRateInfo rate, Money base, Money tax, Money recoverable) {
  }

  /** One request line and its taxes; no taxes for a zero-rated or exempt line. */
  record LineTaxes(String key, Money amount, List<LineTax> taxes) {
    public LineTaxes {
      taxes = List.copyOf(taxes);
    }

    public Money taxTotal() {
      return Money.sum(amount.currency(), taxes.stream().map(LineTax::tax).toList());
    }
  }

  /** One rate on one line: the snapshot a document line stores. */
  record LineTax(TaxRateInfo rate, Money base, Money tax, Money recoverable) {
  }

  /**
   * The rate as it was applied, copied into snapshots so a later rate change
   * never alters an issued document.
   *
   * @param percent 9.975 for 9.975 %
   */
  record TaxRateInfo(String code, String description, TaxKind kind, BigDecimal percent, boolean compound,
      BigDecimal recoverablePercent) {
  }
}
