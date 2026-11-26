package com.lesconstructionssapete.stempyerpserver.modules.gl.operations;

import java.util.Optional;

import com.lesconstructionssapete.stempyerpserver.modules.Operation;
import com.lesconstructionssapete.stempyerpserver.modules.gl.JournalRepository;
import com.lesconstructionssapete.stempyerpserver.modules.gl.StoredEntry;
import com.lesconstructionssapete.stempyerpserver.platform.db.UnitOfWork;

/**
 * One entry by id, whatever its company: the orchestrator checks visibility.
 * {@code lock}: FOR UPDATE, for a reversal.
 */
public class FindJournalEntryOperation implements Operation<FindJournalEntryOperation.Input, Optional<StoredEntry>> {

  public record Input(long id, boolean lock) {
  }

  private final JournalRepository repository;

  public FindJournalEntryOperation(JournalRepository repository) {
    this.repository = repository;
  }

  @Override
  public Optional<StoredEntry> execute(UnitOfWork uow, Input in) {
    return in.lock() ? repository.findForUpdate(uow, in.id()) : repository.find(uow, in.id());
  }
}
