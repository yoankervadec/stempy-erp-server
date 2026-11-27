package com.lesconstructionssapete.stempyerpserver.modules.masterdata.customers;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Collectors;

import com.lesconstructionssapete.stempyerpserver.modules.common.Entities;
import com.lesconstructionssapete.stempyerpserver.modules.masterdata.currencies.Currency;
import com.lesconstructionssapete.stempyerpserver.platform.entity.EntityDefinition;
import com.lesconstructionssapete.stempyerpserver.platform.entity.Field;
import com.lesconstructionssapete.stempyerpserver.platform.entity.Relation;
import com.lesconstructionssapete.stempyerpserver.platform.entity.SortKey;
import com.lesconstructionssapete.stempyerpserver.platform.entity.StandardEntity;
import com.lesconstructionssapete.stempyerpserver.platform.i18n.Language;

/**
 * A customer of the company (customers, docs/ARCHITECTURE.md §26): who it
 * sells to, where it bills them, in which currency and language. Plain master
 * data on the generic entity routes; CustomerService adds the defaults and
 * the address rules the fields can't say.
 *
 * The address is the billing address. countryCode is ISO 3166-1 alpha-2 (CA),
 * provinceCode the subdivision without its country (QC): together, a tax place
 * of supply (CA-QC).
 */
public class Customer extends StandardEntity {

  /** Every ISO 3166-1 alpha-2 country code the JDK knows, as one alternation. */
  static final String COUNTRY_PATTERN = Arrays.stream(Locale.getISOCountries()).sorted()
      .collect(Collectors.joining("|"));

  /** "fr|en": the languages the server writes in. */
  static final String LANGUAGE_PATTERN = Arrays.stream(Language.values()).map(Language::code)
      .collect(Collectors.joining("|"));

  public static final EntityDefinition<Customer> DEF = Entities.define("customer", "customers", Customer::new);

  /**
   * Left out on a create: the next number of the company's customer series
   * (CA000123, CustomerService). Fixed once created.
   */
  public static final Field<Customer, String> CUSTOMER_NO = DEF.string(
      "customerNo", "customer_no", Customer::customerNo, Customer::setCustomerNo)
      .unique().immutable().maxLength(20).pattern("\\S(.*\\S)?").filterable().sortable();

  public static final Field<Customer, String> NAME = DEF.string(
      "name", "name", Customer::name, Customer::setName)
      .required().maxLength(100).filterable().sortable();

  public static final Field<Customer, String> CONTACT_NAME = DEF.string(
      "contactName", "contact_name", Customer::contactName, Customer::setContactName)
      .maxLength(100).filterable().sortable();

  public static final Field<Customer, String> EMAIL = DEF.string(
      "email", "email", Customer::email, Customer::setEmail)
      .maxLength(254).pattern("[^@\\s]+@[^@\\s]+\\.[^@\\s]+").filterable().sortable();

  public static final Field<Customer, String> PHONE = DEF.string(
      "phone", "phone", Customer::phone, Customer::setPhone)
      .maxLength(30).pattern("[0-9+()\\-. ]*(x|ext\\.? ?)?[0-9]*").filterable();

  public static final Field<Customer, String> ADDRESS_LINE1 = DEF.string(
      "addressLine1", "address_line1", Customer::addressLine1, Customer::setAddressLine1)
      .maxLength(100).filterable();

  public static final Field<Customer, String> ADDRESS_LINE2 = DEF.string(
      "addressLine2", "address_line2", Customer::addressLine2, Customer::setAddressLine2)
      .maxLength(100);

  public static final Field<Customer, String> CITY = DEF.string(
      "city", "city", Customer::city, Customer::setCity)
      .maxLength(50).filterable().sortable();

  /** ISO 3166-2 without the country: QC, ON; checked against the country by CustomerService. */
  public static final Field<Customer, String> PROVINCE_CODE = DEF.string(
      "provinceCode", "province_code", Customer::provinceCode, Customer::setProvinceCode)
      .maxLength(3).pattern("[A-Z0-9]{1,3}").filterable().sortable();

  public static final Field<Customer, String> POSTAL_CODE = DEF.string(
      "postalCode", "postal_code", Customer::postalCode, Customer::setPostalCode)
      .maxLength(20).filterable();

  public static final Field<Customer, String> COUNTRY_CODE = DEF.string(
      "countryCode", "country_code", Customer::countryCode, Customer::setCountryCode)
      .required().maxLength(2).pattern(COUNTRY_PATTERN).filterable().sortable();

  public static final Field<Customer, Long> CURRENCY_ID = DEF.integer(
      "currencyId", "currency_id", Customer::currencyId, Customer::setCurrencyId)
      .required().filterable();

  /** Null: the company's default language. */
  public static final Field<Customer, String> LANGUAGE = DEF.string(
      "language", "language", Customer::language, Customer::setLanguage)
      .pattern(LANGUAGE_PATTERN).filterable();

  /** In the company's base currency; null: no limit. */
  public static final Field<Customer, BigDecimal> CREDIT_LIMIT = DEF.decimal(
      "creditLimit", "credit_limit", Customer::creditLimit, Customer::setCreditLimit)
      .min("0").filterable().sortable();

  public static final Field<Customer, String> NOTES = DEF.string(
      "notes", "notes", Customer::notes, Customer::setNotes)
      .maxLength(2000);

  public static final Field<Customer, Boolean> IS_ENABLED = DEF.bool(
      "isEnabled", "is_enabled", Customer::isEnabled, Customer::setEnabled)
      .filterable();

  /** Filters and sorts like "currency.code". */
  public static final Relation<Customer, Currency> CURRENCY_REL = DEF.manyToOne(
      "currency", () -> Currency.DEF, "currency_id");

  /** UI hints (docs/ARCHITECTURE.md §23.4). credit_limit is DECIMAL(19, 4), entered in cents. */
  static {
    CREDIT_LIMIT.scale(2).money();
    NOTES.multiline();
    COUNTRY_CODE.defaultValue("CA");
    IS_ENABLED.defaultValue(true); // a new customer's currency: the company's (CustomerService)
    DEF.subtitleField(NAME)
        .listColumns(CUSTOMER_NO, NAME, CITY, PHONE, EMAIL, IS_ENABLED)
        .defaultSort(SortKey.asc(CUSTOMER_NO));
  }

  private String customerNo;
  private String name;
  private String contactName;
  private String email;
  private String phone;
  private String addressLine1;
  private String addressLine2;
  private String city;
  private String provinceCode;
  private String postalCode;
  private String countryCode;
  private Long currencyId;
  private String language;
  private BigDecimal creditLimit;
  private String notes;
  private boolean enabled = true;

  public String customerNo() {
    return customerNo;
  }

  public void setCustomerNo(String customerNo) {
    this.customerNo = customerNo;
  }

  public String name() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public String contactName() {
    return contactName;
  }

  public void setContactName(String contactName) {
    this.contactName = contactName;
  }

  public String email() {
    return email;
  }

  public void setEmail(String email) {
    this.email = email;
  }

  public String phone() {
    return phone;
  }

  public void setPhone(String phone) {
    this.phone = phone;
  }

  public String addressLine1() {
    return addressLine1;
  }

  public void setAddressLine1(String addressLine1) {
    this.addressLine1 = addressLine1;
  }

  public String addressLine2() {
    return addressLine2;
  }

  public void setAddressLine2(String addressLine2) {
    this.addressLine2 = addressLine2;
  }

  public String city() {
    return city;
  }

  public void setCity(String city) {
    this.city = city;
  }

  public String provinceCode() {
    return provinceCode;
  }

  public void setProvinceCode(String provinceCode) {
    this.provinceCode = provinceCode;
  }

  public String postalCode() {
    return postalCode;
  }

  public void setPostalCode(String postalCode) {
    this.postalCode = postalCode;
  }

  public String countryCode() {
    return countryCode;
  }

  public void setCountryCode(String countryCode) {
    this.countryCode = countryCode;
  }

  public Long currencyId() {
    return currencyId;
  }

  public void setCurrencyId(Long currencyId) {
    this.currencyId = currencyId;
  }

  public String language() {
    return language;
  }

  public void setLanguage(String language) {
    this.language = language;
  }

  public BigDecimal creditLimit() {
    return creditLimit;
  }

  public void setCreditLimit(BigDecimal creditLimit) {
    this.creditLimit = creditLimit;
  }

  public String notes() {
    return notes;
  }

  public void setNotes(String notes) {
    this.notes = notes;
  }

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }
}
