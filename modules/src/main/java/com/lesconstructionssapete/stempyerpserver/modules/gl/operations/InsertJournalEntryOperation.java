package com.lesconstructionssapete.stempyerpserver.modules.gl.operations;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.lesconstructionssapete.stempyerpserver.modules.Operation;
import com.lesconstructionssapete.stempyerpserver.modules.gl.JournalRepository;
import com.lesconstructionssapete.stempyerpserver.modules.gl.PostingLine;
import com.lesconstructionssapete.stempyerpserver.modules.gl.StoredEntry;
import com.lesconstructionssapete.stempyerpserver.platform.audit.AuditEntry;
import com.lesconstructionssapete.stempyerpserver.platform.audit.AuditLog;
import com.lesconstructionssapete.stempyerpserver.platform.db.UnitOfWork;
import com.lesconstructionssapete.stempyerpserver.platform.write.EntityChanged.FieldChange;

/**
 * Inserts a validated, numbered entry and its lines (numbered from 1); returns
 * its id. Audited as POSTED (entity "glJournalEntry").
 */
public class InsertJournalEntryOperation implements Operation<InsertJournalEntryOperation.Input, Long> {

  public static final String POSTED = "POSTED";

  public record Input(long companyId, String entryNo, LocalDate postingDate, long periodId, String description,
      String sourceType, Long sourceId, String sourceNo, Long reversalOfId, String currency, BigDecimal total,
      List<PostingLine> lines, long userId, String requestId, Instant now) {
  }

  private final JournalRepository repository;
  private final AuditLog audit;

  public InsertJournalEntryOperation(JournalRepository repository, AuditLog audit) {
    this.repository = repository;
    this.audit = audit;
  }

  @Override
  public Long execute(UnitOfWork uow, Input in) {
    long id = repository.insertEntry(uow, in.companyId(), in.entryNo(), in.postingDate(), in.periodId(),
        in.description(), in.sourceType(), in.sourceId(), in.sourceNo(), in.reversalOfId(), in.currency(),
        in.total(), in.now(), in.userId());
    int lineNo = 0;
    for (PostingLine line : in.lines()) {
      repository.insertLine(uow, id, ++lineNo, in.companyId(), in.postingDate(), line);
    }
    Map<String, FieldChange> changes = new LinkedHashMap<>();
    changes.put("entryNo", new FieldChange(null, in.entryNo()));
    changes.put("postingDate", new FieldChange(null, in.postingDate().toString()));
    changes.put("sourceType", new FieldChange(null, in.sourceType()));
    changes.put("total", new FieldChange(null, in.total().toPlainString()));
    if (in.reversalOfId() != null) {
      changes.put("reversalOfId", new FieldChange(null, in.reversalOfId()));
    }
    audit.record(uow, new AuditEntry(StoredEntry.AUDIT_ENTITY, id, in.companyId(), POSTED, in.userId(), in.requestId(),
        in.now(), changes));
    return id;
  }
}
