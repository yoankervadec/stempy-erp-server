package com.lesconstructionssapete.stempyerpserver.modules.masterdata.customers;

import com.lesconstructionssapete.stempyerpserver.modules.company.CompanyApi;
import com.lesconstructionssapete.stempyerpserver.modules.masterdata.customers.operations.TakeCustomerNumberOperation;
import com.lesconstructionssapete.stempyerpserver.platform.entity.EntityRegistry;
import com.lesconstructionssapete.stempyerpserver.platform.module.ERPModule;
import com.lesconstructionssapete.stempyerpserver.platform.module.ServiceRegistry;
import com.lesconstructionssapete.stempyerpserver.platform.numbering.DocumentNumbers;
import com.lesconstructionssapete.stempyerpserver.platform.numbering.DocumentType;
import com.lesconstructionssapete.stempyerpserver.platform.numbering.DocumentTypes;
import com.lesconstructionssapete.stempyerpserver.platform.numbering.Mode;
import com.lesconstructionssapete.stempyerpserver.platform.security.PermissionRegistry;
import com.lesconstructionssapete.stempyerpserver.platform.web.EntityEndpoints;
import com.lesconstructionssapete.stempyerpserver.platform.web.RecordLayout;
import com.lesconstructionssapete.stempyerpserver.platform.web.Routes;

/**
 * Master data: customers (docs/ARCHITECTURE.md §26), on the generic entity
 * routes:
 *
 * <pre>
 * QUERY /masterdata/customers   GET / PATCH / DELETE /masterdata/customers/{id}   POST
 * </pre>
 *
 * A customer created without a number gets the next one of the company's
 * "masterdata.customer" series: CA{SEQ:6} by default (CA000123), gaps
 * allowed. Each company can change its series under /numbering.
 *
 * Depends on users (created_by / updated_by relations), masterdata.currencies
 * (a customer's currency) and company (CompanyApi, for a new customer's
 * currency), so it comes after them in AllModules.
 */
public class CustomersModule implements ERPModule {

  static final String PERMISSION = "masterdata.customer";

  @Override
  public String name() {
    return "masterdata.customers";
  }

  @Override
  public void registerPermissions(PermissionRegistry permissions) {
    permissions.declareEntity(PERMISSION, "customers");
  }

  @Override
  public void registerDocumentTypes(DocumentTypes documentTypes) {
    documentTypes.register(DocumentType.of(TakeCustomerNumberOperation.DOCUMENT_TYPE, "Customers")
        .withDefaultSeries("CA", "CA{SEQ:6}", Mode.GAPS_ALLOWED));
  }

  @Override
  public void registerEntities(EntityRegistry entityRegistry) {
    entityRegistry.registerAll(Customer.class);
  }

  @Override
  public void registerRoutes(Routes routes, ServiceRegistry serviceRegistry) {
    EntityEndpoints crud = serviceRegistry.get(EntityEndpoints.class);
    crud.register(routes, "/masterdata/customers", Customer.DEF, PERMISSION);
    crud.rules(Customer.DEF, new CustomerService(serviceRegistry.get(CompanyApi.class),
        new TakeCustomerNumberOperation(serviceRegistry.get(DocumentNumbers.class), new CustomerRepository())));
    crud.layout(Customer.DEF, RecordLayout.of(Customer.DEF)
        .section("general", 2, Customer.CUSTOMER_NO, Customer.NAME, Customer.CONTACT_NAME, Customer.IS_ENABLED)
        .section("contact", 2, Customer.EMAIL, Customer.PHONE, Customer.LANGUAGE)
        .section("address", 2, Customer.ADDRESS_LINE1, Customer.ADDRESS_LINE2, Customer.CITY,
            Customer.PROVINCE_CODE, Customer.POSTAL_CODE, Customer.COUNTRY_CODE)
        .section("billing", 2, Customer.CURRENCY_ID, Customer.CREDIT_LIMIT)
        .section("notes", 1, Customer.NOTES)
        .tab(RecordLayout.HISTORY));
  }
}
