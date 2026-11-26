package com.lesconstructionssapete.stempyerpserver.modules.gl.operations;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

import com.lesconstructionssapete.stempyerpserver.modules.Operation;
import com.lesconstructionssapete.stempyerpserver.modules.gl.Account;
import com.lesconstructionssapete.stempyerpserver.modules.gl.AccountRepository;
import com.lesconstructionssapete.stempyerpserver.modules.gl.DimensionRepository;
import com.lesconstructionssapete.stempyerpserver.modules.gl.DimensionRepository.DimensionRow;
import com.lesconstructionssapete.stempyerpserver.modules.gl.DimensionRule;
import com.lesconstructionssapete.stempyerpserver.modules.gl.StoredAccount;
import com.lesconstructionssapete.stempyerpserver.platform.audit.AuditEntry;
import com.lesconstructionssapete.stempyerpserver.platform.audit.AuditLog;
import com.lesconstructionssapete.stempyerpserver.platform.db.UnitOfWork;
import com.lesconstructionssapete.stempyerpserver.platform.error.ConflictException;
import com.lesconstructionssapete.stempyerpserver.platform.error.ResourceNotFoundException;
import com.lesconstructionssapete.stempyerpserver.platform.error.Validation;
import com.lesconstructionssapete.stempyerpserver.platform.write.EntityChanged.FieldChange;

/**
 * Replaces an account's dimension rules as a whole, if nobody changed the
 * account since {@code version} was read (the account row is locked first).
 * Rules are part of the account: its version goes up, and the change is
 * audited as UPDATED (entity "glAccount") with {@code dimensionRules} old and
 * new, as {code: rule} maps. Nothing changes, nothing recorded, if the rules
 * are the same.
 */
public class ReplaceDimensionRulesOperation implements Operation<ReplaceDimensionRulesOperation.Input, Void> {

  /** @param rules dimension code -> rule; empty: no rules */
  public record Input(long accountId, int version, Map<String, DimensionRule> rules, long userId, String requestId,
      Instant now) {
  }

  private final AccountRepository accounts;
  private final DimensionRepository dimensions;
  private final AuditLog audit;

  public ReplaceDimensionRulesOperation(AccountRepository accounts, DimensionRepository dimensions, AuditLog audit) {
    this.accounts = accounts;
    this.dimensions = dimensions;
    this.audit = audit;
  }

  @Override
  public Void execute(UnitOfWork uow, Input in) {
    StoredAccount account = accounts.findForUpdate(uow, in.accountId())
        .orElseThrow(() -> new ResourceNotFoundException("NOT_FOUND", "Account " + in.accountId() + " not found"));
    if (account.version() != in.version()) {
      throw new ConflictException("VERSION_CONFLICT", "The account was changed by someone else; reload and retry");
    }
    var v = Validation.check();
    Map<Long, DimensionRule> byId = new LinkedHashMap<>();
    Map<String, String> after = new TreeMap<>();
    in.rules().forEach((code, rule) -> {
      var dimension = dimensions.findDimension(uow, account.companyId(), code);
      if (dimension.isEmpty()) {
        v.add("rules." + code, "UNKNOWN_DIMENSION", "no dimension " + code + " in this company");
      } else if (byId.put(dimension.get().id(), rule) != null) {
        v.add("rules." + code, "DUPLICATE_DIMENSION", "dimension " + dimension.get().code() + " is listed twice");
      } else {
        after.put(dimension.map(DimensionRow::code).get(), rule.name());
      }
    });
    v.throwIfInvalid();

    Map<String, String> before = new TreeMap<>();
    dimensions.rules(uow, account.id()).forEach(r -> before.put(r.dimensionCode(), r.rule().name()));
    if (before.equals(after)) {
      return null;
    }
    dimensions.deleteRules(uow, account.id());
    byId.forEach((dimensionId, rule) -> dimensions.insertRule(uow, account.id(), dimensionId, rule));
    accounts.touch(uow, account.id(), in.userId(), in.now());
    audit.record(uow, new AuditEntry(Account.DEF.name(), account.id(), account.companyId(), AuditEntry.UPDATED,
        in.userId(), in.requestId(), in.now(), Map.of("dimensionRules", new FieldChange(before, after))));
    return null;
  }
}
