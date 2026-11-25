package com.lesconstructionssapete.stempyerpserver.modules.system.numbering.operations;

import java.time.Instant;
import java.util.Map;

import com.lesconstructionssapete.stempyerpserver.modules.Operation;
import com.lesconstructionssapete.stempyerpserver.modules.system.numbering.NumberSeriesAdminRepository;
import com.lesconstructionssapete.stempyerpserver.modules.system.numbering.SeriesFields;
import com.lesconstructionssapete.stempyerpserver.modules.system.numbering.StoredSeries;
import com.lesconstructionssapete.stempyerpserver.platform.audit.AuditEntry;
import com.lesconstructionssapete.stempyerpserver.platform.audit.AuditLog;
import com.lesconstructionssapete.stempyerpserver.platform.db.UnitOfWork;
import com.lesconstructionssapete.stempyerpserver.platform.error.ConflictException;
import com.lesconstructionssapete.stempyerpserver.platform.error.ResourceNotFoundException;
import com.lesconstructionssapete.stempyerpserver.platform.write.EntityChanged.FieldChange;

/**
 * Saves validated changes if nobody changed the series since {@code version}
 * was read. The row is locked first, so the "used" check holds until the
 * commit: a counter's first insert waits on it.
 *
 * <ul>
 * <li>pattern, mode, start value: only while the series has no counter
 * ({@code NUMBER_SERIES_IN_USE}, 409);</li>
 * <li>becoming the default takes the flag off the old default, in the same
 * transaction.</li>
 * </ul>
 *
 * Audited as UPDATED (entity "numberSeries") with the fields that changed;
 * nothing is recorded if none did.
 */
public class UpdateSeriesOperation implements Operation<UpdateSeriesOperation.Input, Void> {

  public static final String IN_USE = "NUMBER_SERIES_IN_USE";

  public record Input(long id, int version, SeriesFields fields, long userId, String requestId, Instant now) {
  }

  private final NumberSeriesAdminRepository repository;
  private final AuditLog audit;

  public UpdateSeriesOperation(NumberSeriesAdminRepository repository, AuditLog audit) {
    this.repository = repository;
    this.audit = audit;
  }

  @Override
  public Void execute(UnitOfWork uow, Input in) {
    StoredSeries current = repository.findForUpdate(uow, in.id())
        .orElseThrow(() -> new ResourceNotFoundException("NOT_FOUND", "Number series " + in.id() + " not found"));
    if (current.version() != in.version()) {
      throw new ConflictException("VERSION_CONFLICT", "The series was changed by someone else; reload and retry");
    }
    SeriesFields before = current.fields();
    SeriesFields after = in.fields();
    if (current.used() && (!before.pattern().equals(after.pattern()) || before.mode() != after.mode()
        || before.startValue() != after.startValue())) {
      throw new ConflictException(IN_USE, "The series has issued numbers: its pattern, mode and start value "
          + "can no longer change. Create a new series and make it the default instead.");
    }
    if (after.isDefault() && !before.isDefault()) {
      repository.findDefaultForUpdate(uow, current.companyId(), current.documentType())
          .filter(old -> old.id() != current.id())
          .ifPresent(old -> {
            repository.clearDefault(uow, old.id(), in.userId(), in.now());
            audit.record(uow, new AuditEntry(SeriesFields.AUDIT_ENTITY, old.id(), old.companyId(),
                AuditEntry.UPDATED, in.userId(), in.requestId(), in.now(),
                Map.of("isDefault", new FieldChange(true, false))));
          });
    }
    if (repository.update(uow, current.id(), in.version(), after, in.userId(), in.now()) == 0) {
      throw new IllegalStateException("number series " + current.id() + " changed while locked");
    }
    Map<String, FieldChange> changes = SeriesFields.changes(before, after);
    if (!changes.isEmpty()) {
      audit.record(uow, new AuditEntry(SeriesFields.AUDIT_ENTITY, current.id(), current.companyId(),
          AuditEntry.UPDATED, in.userId(), in.requestId(), in.now(), changes));
    }
    return null;
  }
}
