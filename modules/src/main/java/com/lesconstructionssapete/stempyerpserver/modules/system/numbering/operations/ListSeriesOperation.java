package com.lesconstructionssapete.stempyerpserver.modules.system.numbering.operations;

import java.util.List;

import com.lesconstructionssapete.stempyerpserver.modules.Operation;
import com.lesconstructionssapete.stempyerpserver.modules.system.numbering.NumberSeriesAdminRepository;
import com.lesconstructionssapete.stempyerpserver.modules.system.numbering.StoredSeries;
import com.lesconstructionssapete.stempyerpserver.platform.db.UnitOfWork;

/** A company's series, optionally of one document type, by type then code. */
public class ListSeriesOperation implements Operation<ListSeriesOperation.Input, List<StoredSeries>> {

  /** @param documentType null: every type */
  public record Input(long companyId, String documentType, int limit, int offset) {
  }

  private final NumberSeriesAdminRepository repository;

  public ListSeriesOperation(NumberSeriesAdminRepository repository) {
    this.repository = repository;
  }

  @Override
  public List<StoredSeries> execute(UnitOfWork uow, Input in) {
    return repository.list(uow, in.companyId(), in.documentType(), in.limit(), in.offset());
  }
}
