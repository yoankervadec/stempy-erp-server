package com.lesconstructionssapete.stempyerpserver.modules.gl;

import java.time.Clock;
import java.time.Instant;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.SortedMap;

import com.fasterxml.jackson.databind.JsonNode;
import com.lesconstructionssapete.stempyerpserver.modules.gl.DimensionRepository.SetEntry;
import com.lesconstructionssapete.stempyerpserver.modules.gl.dto.DimensionRuleView;
import com.lesconstructionssapete.stempyerpserver.modules.gl.operations.DescribeDimensionSetOperation;
import com.lesconstructionssapete.stempyerpserver.modules.gl.operations.FindAccountOperation;
import com.lesconstructionssapete.stempyerpserver.modules.gl.operations.FindOrCreateDimensionSetOperation;
import com.lesconstructionssapete.stempyerpserver.modules.gl.operations.ListDimensionRulesOperation;
import com.lesconstructionssapete.stempyerpserver.modules.gl.operations.ReplaceDimensionRulesOperation;
import com.lesconstructionssapete.stempyerpserver.modules.gl.operations.ResolveDimensionsOperation;
import com.lesconstructionssapete.stempyerpserver.platform.db.UnitOfWorkFactory;
import com.lesconstructionssapete.stempyerpserver.platform.error.ResourceNotFoundException;
import com.lesconstructionssapete.stempyerpserver.platform.error.Validation;
import com.lesconstructionssapete.stempyerpserver.platform.error.ValidationFailedException;
import com.lesconstructionssapete.stempyerpserver.platform.security.PermissionChecker;
import com.lesconstructionssapete.stempyerpserver.platform.web.RequestContext;

/**
 * Dimensions beyond what the generic entity routes do (orchestrator),
 * docs/ARCHITECTURE.md §22.3:
 *
 * <ul>
 * <li>account dimension rules, read with gl.account.read and replaced with
 * gl.account.manage (they are part of the account);</li>
 * <li>dimension sets, for posting: {@link #resolveSet} turns a line's
 * {dimension code: value code} into a set id, creating the set on first
 * use.</li>
 * </ul>
 */
public class DimensionService {

  public static final String READ = "gl.dimension.read";
  public static final String CREATE = "gl.dimension.create";
  public static final String UPDATE = "gl.dimension.update";

  private final UnitOfWorkFactory uowFactory;
  private final PermissionChecker permissions;
  private final Clock clock;
  private final FindAccountOperation findAccount;
  private final ListDimensionRulesOperation listRules;
  private final ReplaceDimensionRulesOperation replaceRules;
  private final ResolveDimensionsOperation resolveDimensions;
  private final FindOrCreateDimensionSetOperation findOrCreateSet;
  private final DescribeDimensionSetOperation describeSet;

  public DimensionService(UnitOfWorkFactory uowFactory, PermissionChecker permissions, Clock clock,
      FindAccountOperation findAccount, ListDimensionRulesOperation listRules,
      ReplaceDimensionRulesOperation replaceRules, ResolveDimensionsOperation resolveDimensions,
      FindOrCreateDimensionSetOperation findOrCreateSet, DescribeDimensionSetOperation describeSet) {
    this.uowFactory = uowFactory;
    this.permissions = permissions;
    this.clock = clock;
    this.findAccount = findAccount;
    this.listRules = listRules;
    this.replaceRules = replaceRules;
    this.resolveDimensions = resolveDimensions;
    this.findOrCreateSet = findOrCreateSet;
    this.describeSet = describeSet;
  }

  // ---- account rules ----

  public List<DimensionRuleView> rules(RequestContext rc, long accountId) {
    permissions.require(rc, AccountService.READ);
    visibleAccount(rc, accountId);
    return uowFactory.executeReadOnly(uow -> listRules.execute(uow, accountId)).stream()
        .map(r -> new DimensionRuleView(r.dimensionCode(), r.rule())).toList();
  }

  /**
   * {"version": N, "rules": {"PROJECT": "REQUIRED", "STORE": "NOT_ALLOWED"}}:
   * the account's rules as a whole; {} removes them all. Returns the new rules.
   */
  public List<DimensionRuleView> replaceRules(RequestContext rc, long accountId, JsonNode body) {
    permissions.require(rc, AccountService.MANAGE);
    visibleAccount(rc, accountId);
    RulesForm form = rulesForm(body);
    Instant now = clock.instant();
    uowFactory.run(uow -> replaceRules.execute(uow, new ReplaceDimensionRulesOperation.Input(accountId,
        form.version(), form.rules(), rc.userId(), rc.requestId(), now)));
    return rules(rc, accountId);
  }

  // ---- dimension sets (for posting; no HTTP route) ----

  /**
   * The set for {dimension code: value code}, created on first use in a short
   * transaction of its own. Empty for no dimensions.
   *
   * @param fieldPrefix how problems are reported, e.g. "lines[2].dimensions"
   * @throws ValidationFailedException an unknown or disabled dimension or value
   */
  public Optional<Long> resolveSet(long companyId, Map<String, String> codes, String fieldPrefix) {
    if (codes.isEmpty()) {
      return Optional.empty();
    }
    SortedMap<Long, Long> pairs = uowFactory.executeReadOnly(uow -> resolveDimensions.execute(uow,
        new ResolveDimensionsOperation.Input(companyId, codes, fieldPrefix)));
    return Optional.of(uowFactory.execute(uow -> findOrCreateSet.execute(uow,
        new FindOrCreateDimensionSetOperation.Input(companyId, pairs))));
  }

  /** A set's {dimension code: value code}, by dimension code. */
  public Map<String, String> describeSet(long setId) {
    Map<String, String> out = new LinkedHashMap<>();
    for (SetEntry e : uowFactory.executeReadOnly(uow -> describeSet.execute(uow, setId))) {
      out.put(e.dimensionCode(), e.valueCode());
    }
    return out;
  }

  // ---- rules ----

  private void visibleAccount(RequestContext rc, long accountId) {
    uowFactory.executeReadOnly(uow -> findAccount.execute(uow, accountId))
        .filter(a -> a.companyId() == rc.companyId())
        .orElseThrow(() -> new ResourceNotFoundException("NOT_FOUND", "Account " + accountId + " not found"));
  }

  private record RulesForm(int version, Map<String, DimensionRule> rules) {
  }

  private static RulesForm rulesForm(JsonNode body) {
    if (body == null || !body.isObject()) {
      throw new ValidationFailedException("The request body must be a JSON object");
    }
    var v = Validation.check();
    Integer version = null;
    Map<String, DimensionRule> rules = new LinkedHashMap<>();
    for (Iterator<Map.Entry<String, JsonNode>> it = body.fields(); it.hasNext();) {
      var e = it.next();
      JsonNode n = e.getValue();
      switch (e.getKey()) {
        case "version" -> {
          if (n.isIntegralNumber() && n.canConvertToInt() && n.intValue() >= 0) {
            version = n.intValue();
          } else {
            v.add("version", "INVALID_TYPE", "must be a whole number");
          }
        }
        case "rules" -> {
          if (!n.isObject()) {
            v.add("rules", "INVALID_TYPE", "must be an object of {dimension code: rule}");
          } else {
            n.fields().forEachRemaining(r -> {
              try {
                rules.put(r.getKey(), DimensionRule.valueOf(r.getValue().asText().strip().toUpperCase(Locale.ROOT)));
              } catch (IllegalArgumentException ex) {
                v.add("rules." + r.getKey(), "INVALID_VALUE", "must be REQUIRED or NOT_ALLOWED");
              }
            });
          }
        }
        default -> v.add(e.getKey(), "UNKNOWN_FIELD", "unknown field '" + e.getKey() + "'");
      }
    }
    if (!body.has("version")) {
      v.add("version", "REQUIRED", "send the account's version you last read");
    }
    if (!body.has("rules")) {
      v.add("rules", "REQUIRED", "send every rule the account should have ({} for none)");
    }
    v.throwIfInvalid();
    return new RulesForm(version, rules);
  }
}
