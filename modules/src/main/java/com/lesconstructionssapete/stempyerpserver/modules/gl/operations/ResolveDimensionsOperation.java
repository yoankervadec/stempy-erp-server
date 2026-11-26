package com.lesconstructionssapete.stempyerpserver.modules.gl.operations;

import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

import com.lesconstructionssapete.stempyerpserver.modules.Operation;
import com.lesconstructionssapete.stempyerpserver.modules.gl.DimensionRepository;
import com.lesconstructionssapete.stempyerpserver.platform.db.UnitOfWork;
import com.lesconstructionssapete.stempyerpserver.platform.error.Validation;

/**
 * Turns {dimension code: value code} into {dimension id: value id}, checking
 * that every dimension and value exists in the company and is enabled. Every
 * problem is reported at once, as {@code fieldPrefix.DIMENSION_CODE}:
 * UNKNOWN_DIMENSION, DIMENSION_DISABLED, UNKNOWN_VALUE, VALUE_DISABLED,
 * DUPLICATE_DIMENSION (the same dimension under two spellings).
 */
public class ResolveDimensionsOperation implements Operation<ResolveDimensionsOperation.Input,
    SortedMap<Long, Long>> {

  /**
   * @param codes       dimension code -> value code
   * @param fieldPrefix where the codes came from in the request, e.g.
   *                    "lines[2].dimensions"
   */
  public record Input(long companyId, Map<String, String> codes, String fieldPrefix) {
  }

  private final DimensionRepository repository;

  public ResolveDimensionsOperation(DimensionRepository repository) {
    this.repository = repository;
  }

  @Override
  public SortedMap<Long, Long> execute(UnitOfWork uow, Input in) {
    var v = Validation.check();
    SortedMap<Long, Long> ids = new TreeMap<>();
    in.codes().forEach((dimensionCode, valueCode) -> {
      String field = in.fieldPrefix() + "." + dimensionCode;
      var pair = repository.findPair(uow, in.companyId(), dimensionCode, valueCode);
      if (pair.isEmpty()) {
        v.add(field, "UNKNOWN_DIMENSION", "no dimension " + dimensionCode + " in this company");
        return;
      }
      var p = pair.get();
      if (!p.dimensionEnabled()) {
        v.add(field, "DIMENSION_DISABLED", "dimension " + p.dimensionCode() + " is disabled");
      } else if (p.valueId() == null) {
        v.add(field, "UNKNOWN_VALUE", "dimension " + p.dimensionCode() + " has no value " + valueCode);
      } else if (!p.valueEnabled()) {
        v.add(field, "VALUE_DISABLED", p.dimensionCode() + " " + p.valueCode() + " is disabled");
      } else if (ids.put(p.dimensionId(), p.valueId()) != null) {
        v.add(field, "DUPLICATE_DIMENSION", "dimension " + p.dimensionCode() + " is given twice");
      }
    });
    v.throwIfInvalid();
    return ids;
  }
}
