package com.lesconstructionssapete.stempyerpserver.modules.system.numbering.operations;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import com.lesconstructionssapete.stempyerpserver.modules.Operation;
import com.lesconstructionssapete.stempyerpserver.modules.system.numbering.NumberSeriesAdminRepository;
import com.lesconstructionssapete.stempyerpserver.modules.system.numbering.SeriesFields;
import com.lesconstructionssapete.stempyerpserver.platform.audit.AuditEntry;
import com.lesconstructionssapete.stempyerpserver.platform.audit.AuditLog;
import com.lesconstructionssapete.stempyerpserver.platform.db.UnitOfWork;
import com.lesconstructionssapete.stempyerpserver.platform.error.ValidationFailedException;
import com.lesconstructionssapete.stempyerpserver.platform.write.EntityChanged.FieldChange;

/**
 * Inserts a validated series; returns its id. Codes are unique per company and
 * document type. A new default takes the flag off the old one, in the same
 * transaction. When the request didn't say ({@code defaultGiven} false), the
 * series is the default only if the type has none yet. Audited as CREATED (entity "numberSeries"), and the old default
 * as UPDATED.
 */
public class CreateSeriesOperation implements Operation<CreateSeriesOperation.Input, Long> {

  public record Input(long companyId, String documentType, SeriesFields fields, boolean defaultGiven, long userId,
      String requestId, Instant now) {
  }

  private final NumberSeriesAdminRepository repository;
  private final AuditLog audit;

  public CreateSeriesOperation(NumberSeriesAdminRepository repository, AuditLog audit) {
    this.repository = repository;
    this.audit = audit;
  }

  @Override
  public Long execute(UnitOfWork uow, Input in) {
    if (repository.codeTaken(uow, in.companyId(), in.documentType(), in.fields().code())) {
      throw ValidationFailedException.field("code", "ALREADY_EXISTS",
          "another series of " + in.documentType() + " already has this code");
    }
    var oldDefault = repository.findDefaultForUpdate(uow, in.companyId(), in.documentType());
    SeriesFields fields = in.defaultGiven() ? in.fields() : in.fields().withDefault(oldDefault.isEmpty());
    if (fields.isDefault()) {
      // The old default first: at most one default per type (uq_number_series_default).
      oldDefault.ifPresent(old -> {
        repository.clearDefault(uow, old.id(), in.userId(), in.now());
        audit.record(uow, new AuditEntry(SeriesFields.AUDIT_ENTITY, old.id(), in.companyId(), AuditEntry.UPDATED,
            in.userId(), in.requestId(), in.now(), Map.of("isDefault", new FieldChange(true, false))));
      });
    }
    long id = repository.insert(uow, in.companyId(), in.documentType(), fields, in.userId(), in.now());
    Map<String, FieldChange> changes = new LinkedHashMap<>();
    changes.put("documentType", new FieldChange(null, in.documentType()));
    changes.putAll(SeriesFields.changes(null, fields));
    audit.record(uow, new AuditEntry(SeriesFields.AUDIT_ENTITY, id, in.companyId(), AuditEntry.CREATED,
        in.userId(), in.requestId(), in.now(), changes));
    return id;
  }
}
