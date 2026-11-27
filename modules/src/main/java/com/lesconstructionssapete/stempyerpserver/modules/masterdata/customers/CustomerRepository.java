package com.lesconstructionssapete.stempyerpserver.modules.masterdata.customers;

import com.lesconstructionssapete.stempyerpserver.platform.db.Jdbc;
import com.lesconstructionssapete.stempyerpserver.platform.db.UnitOfWork;

/** What the customers module reads beyond the generic routes. Raw JDBC, no rules. */
public class CustomerRepository {

  /** Whether a customer of the company has this number, deleted ones included (their numbers stay taken). */
  public boolean numberTaken(UnitOfWork uow, long companyId, String customerNo) {
    return Jdbc.queryFirst(uow, "SELECT 1 FROM customers WHERE company_id = ? AND customer_no = ?",
        rs -> true, companyId, customerNo).isPresent();
  }
}
