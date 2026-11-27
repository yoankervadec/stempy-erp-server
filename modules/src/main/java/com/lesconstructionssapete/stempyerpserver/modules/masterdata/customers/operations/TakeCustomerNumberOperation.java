package com.lesconstructionssapete.stempyerpserver.modules.masterdata.customers.operations;

import com.lesconstructionssapete.stempyerpserver.modules.Operation;
import com.lesconstructionssapete.stempyerpserver.modules.masterdata.customers.CustomerRepository;
import com.lesconstructionssapete.stempyerpserver.platform.db.UnitOfWork;
import com.lesconstructionssapete.stempyerpserver.platform.numbering.DocumentNumbers;
import com.lesconstructionssapete.stempyerpserver.platform.numbering.NumberRequest;

/**
 * The next free number of the company's customer series. A number someone
 * already typed in (an imported customer) is skipped: the series allows gaps.
 * Input: the company id.
 */
public class TakeCustomerNumberOperation implements Operation<Long, String> {

  /** DocumentType key of customer numbers. */
  public static final String DOCUMENT_TYPE = "masterdata.customer";

  /** Enough to step over a block of imported numbers; more means the series and the imports overlap. */
  static final int MAX_SKIPS = 1_000;

  private final DocumentNumbers numbers;
  private final CustomerRepository customers;

  public TakeCustomerNumberOperation(DocumentNumbers numbers, CustomerRepository customers) {
    this.numbers = numbers;
    this.customers = customers;
  }

  @Override
  public String execute(UnitOfWork uow, Long companyId) {
    for (int i = 0; i <= MAX_SKIPS; i++) {
      String no = numbers.next(uow, NumberRequest.of(companyId, DOCUMENT_TYPE));
      if (!customers.numberTaken(uow, companyId, no)) {
        return no;
      }
    }
    throw new IllegalStateException("The customer series of company " + companyId + " gave " + (MAX_SKIPS + 1)
        + " numbers already in use: move its counter past the imported numbers");
  }
}
