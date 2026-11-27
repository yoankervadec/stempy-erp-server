package com.lesconstructionssapete.stempyerpserver.modules.masterdata.customers;

import java.util.Map;

import com.lesconstructionssapete.stempyerpserver.modules.company.CompanyApi;
import com.lesconstructionssapete.stempyerpserver.modules.masterdata.customers.operations.TakeCustomerNumberOperation;
import com.lesconstructionssapete.stempyerpserver.platform.db.UnitOfWork;
import com.lesconstructionssapete.stempyerpserver.platform.error.Validation;
import com.lesconstructionssapete.stempyerpserver.platform.web.RecordRules;
import com.lesconstructionssapete.stempyerpserver.platform.web.RequestContext;

/**
 * The customers' RecordRules (docs/ARCHITECTURE.md §26): a new customer gets
 * the next number of the company's series when it has none (CA000123), is
 * billed in the company's base currency, and a Canadian address has a real
 * province and a well-formed postal code (AddressRules). Customers are
 * otherwise plain records, written by the generic routes.
 */
public class CustomerService implements RecordRules {

  private final CompanyApi company;
  private final TakeCustomerNumberOperation takeNumber;

  public CustomerService(CompanyApi company, TakeCustomerNumberOperation takeNumber) {
    this.company = company;
    this.takeNumber = takeNumber;
  }

  @Override
  public Map<String, Object> assignOnCreate(UnitOfWork uow, RequestContext rc, Map<String, Object> record) {
    if (record.get(Customer.CUSTOMER_NO.name()) != null) {
      return Map.of(); // typed in (an imported customer): kept
    }
    return Map.of(Customer.CUSTOMER_NO.name(), takeNumber.execute(uow, rc.companyId()));
  }

  @Override
  public Map<String, Object> defaults(UnitOfWork uow, RequestContext rc) {
    return company.baseCurrencyId(uow, rc.companyId())
        .<Map<String, Object>>map(id -> Map.of(Customer.CURRENCY_ID.name(), id))
        .orElse(Map.of());
  }

  @Override
  public void validateWrite(UnitOfWork uow, RequestContext rc, Map<String, Object> record, Validation v) {
    AddressRules.check((String) record.get(Customer.COUNTRY_CODE.name()),
        (String) record.get(Customer.PROVINCE_CODE.name()),
        (String) record.get(Customer.POSTAL_CODE.name()), Customer.PROVINCE_CODE.name(),
        Customer.POSTAL_CODE.name(), v);
  }
}
