package com.lesconstructionssapete.stempyerpserver.modules.gl.operations;

import java.time.Instant;
import java.util.Map;

import com.lesconstructionssapete.stempyerpserver.modules.Operation;
import com.lesconstructionssapete.stempyerpserver.modules.gl.JournalRepository;
import com.lesconstructionssapete.stempyerpserver.modules.gl.StoredEntry;
import com.lesconstructionssapete.stempyerpserver.platform.audit.AuditEntry;
import com.lesconstructionssapete.stempyerpserver.platform.audit.AuditLog;
import com.lesconstructionssapete.stempyerpserver.platform.db.UnitOfWork;
import com.lesconstructionssapete.stempyerpserver.platform.error.ConflictException;
import com.lesconstructionssapete.stempyerpserver.platform.write.EntityChanged.FieldChange;

/**
 * Links an entry to the reversal just posted for it: the only change a posted
 * entry ever gets. Audited as REVERSED (entity "glJournalEntry") on the
 * original.
 */
public class MarkReversedOperation implements Operation<MarkReversedOperation.Input, Void> {

  public static final String REVERSED = "REVERSED";

  public record Input(long entryId, long companyId, long reversalId, String reversalNo, long userId,
      String requestId, Instant now) {
  }

  private final JournalRepository repository;
  private final AuditLog audit;

  public MarkReversedOperation(JournalRepository repository, AuditLog audit) {
    this.repository = repository;
    this.audit = audit;
  }

  @Override
  public Void execute(UnitOfWork uow, Input in) {
    if (!repository.setReversedBy(uow, in.companyId(), in.entryId(), in.reversalId())) {
      throw new ConflictException("ALREADY_REVERSED", "The entry has already been reversed");
    }
    audit.record(uow, new AuditEntry(StoredEntry.AUDIT_ENTITY, in.entryId(), in.companyId(),
        REVERSED, in.userId(), in.requestId(), in.now(),
        Map.of("reversedBy", new FieldChange(null, in.reversalNo()))));
    return null;
  }
}
