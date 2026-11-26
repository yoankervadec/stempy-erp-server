package com.lesconstructionssapete.stempyerpserver.modules.gl.operations;

import java.util.List;

import com.lesconstructionssapete.stempyerpserver.modules.Operation;
import com.lesconstructionssapete.stempyerpserver.modules.gl.JournalRepository;
import com.lesconstructionssapete.stempyerpserver.modules.gl.StoredLine;
import com.lesconstructionssapete.stempyerpserver.platform.db.UnitOfWork;

/** An entry's lines, in order, with their accounts' numbers and names. */
public class ListJournalLinesOperation implements Operation<Long, List<StoredLine>> {

  private final JournalRepository repository;

  public ListJournalLinesOperation(JournalRepository repository) {
    this.repository = repository;
  }

  @Override
  public List<StoredLine> execute(UnitOfWork uow, Long entryId) {
    return repository.lines(uow, entryId);
  }
}
