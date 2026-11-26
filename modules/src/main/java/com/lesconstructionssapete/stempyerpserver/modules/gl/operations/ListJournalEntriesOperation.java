package com.lesconstructionssapete.stempyerpserver.modules.gl.operations;

import java.time.LocalDate;
import java.util.List;

import com.lesconstructionssapete.stempyerpserver.modules.Operation;
import com.lesconstructionssapete.stempyerpserver.modules.gl.JournalRepository;
import com.lesconstructionssapete.stempyerpserver.modules.gl.StoredEntry;
import com.lesconstructionssapete.stempyerpserver.platform.db.UnitOfWork;

/** A company's entries, newest first, optionally by date range and source type. */
public class ListJournalEntriesOperation implements Operation<ListJournalEntriesOperation.Input, List<StoredEntry>> {

  /**
   * @param from       null: no lower bound
   * @param to         null: no upper bound
   * @param sourceType null: any
   */
  public record Input(long companyId, LocalDate from, LocalDate to, String sourceType, int limit, int offset) {
  }

  private final JournalRepository repository;

  public ListJournalEntriesOperation(JournalRepository repository) {
    this.repository = repository;
  }

  @Override
  public List<StoredEntry> execute(UnitOfWork uow, Input in) {
    return repository.list(uow, in.companyId(), in.from(), in.to(), in.sourceType(), in.limit(), in.offset());
  }
}
