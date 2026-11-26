package com.lesconstructionssapete.stempyerpserver.modules.masterdata.items;

import java.util.Optional;

import com.lesconstructionssapete.stempyerpserver.platform.db.Jdbc;
import com.lesconstructionssapete.stempyerpserver.platform.db.UnitOfWork;

/** What the items module reads beyond its own entities. Raw JDBC, no rules. */
public class ItemRepository {

  /** The currencies row of the company's base currency (companies.base_currency_code), if there is one. */
  public Optional<Long> findBaseCurrencyId(UnitOfWork uow, long companyId) {
    return Jdbc.queryFirst(uow, """
        SELECT cu.id FROM companies c JOIN currencies cu ON cu.code = c.base_currency_code
        WHERE c.id = ?""", rs -> rs.getLong("id"), companyId);
  }
}
