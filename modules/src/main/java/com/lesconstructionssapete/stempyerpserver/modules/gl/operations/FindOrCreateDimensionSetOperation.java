package com.lesconstructionssapete.stempyerpserver.modules.gl.operations;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.SortedMap;
import java.util.stream.Collectors;

import com.lesconstructionssapete.stempyerpserver.modules.Operation;
import com.lesconstructionssapete.stempyerpserver.modules.gl.DimensionRepository;
import com.lesconstructionssapete.stempyerpserver.platform.db.UnitOfWork;

/**
 * The id of the set holding exactly these (dimension id, value id) pairs,
 * creating it on first use. Run it in a short transaction of its own: the
 * set's row and entries are inserted and committed together, and a
 * concurrent first use waits on the hash's key, then reads the same set.
 *
 * The pairs must be resolved first (ResolveDimensionsOperation); this
 * checks nothing.
 */
public class FindOrCreateDimensionSetOperation implements Operation<FindOrCreateDimensionSetOperation.Input, Long> {

  /** @param pairs dimension id -> value id, non-empty */
  public record Input(long companyId, SortedMap<Long, Long> pairs) {
  }

  private final DimensionRepository repository;

  public FindOrCreateDimensionSetOperation(DimensionRepository repository) {
    this.repository = repository;
  }

  @Override
  public Long execute(UnitOfWork uow, Input in) {
    if (in.pairs().isEmpty()) {
      throw new IllegalArgumentException("a dimension set needs at least one pair");
    }
    String hash = hash(in.pairs());
    boolean created = repository.insertSetIfAbsent(uow, in.companyId(), hash);
    long setId = repository.setId(uow, in.companyId(), hash);
    if (created) {
      in.pairs().forEach((dimensionId, valueId) -> repository.insertSetEntry(uow, setId, dimensionId, valueId));
    }
    return setId;
  }

  /** SHA-256 (hex) of "dimensionId=valueId;..." in dimension id order. */
  static String hash(SortedMap<Long, Long> pairs) {
    String canonical = pairs.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue())
        .collect(Collectors.joining(";"));
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
          .digest(canonical.getBytes(StandardCharsets.US_ASCII)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is missing from this JVM", e);
    }
  }
}
