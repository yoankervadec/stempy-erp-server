package com.lesconstructionssapete.stempyerpserver.platform.query;

import java.util.List;

import com.lesconstructionssapete.stempyerpserver.platform.entity.EntityDefinition;

/**
 * A translated partial UPDATE. `existsSql` is run only when 0 rows were
 * updated, to tell "no such row" (404) apart from "row exists but version is
 * stale" (409).
 */
public record SQLUpdate<E>(
    EntityDefinition<E> entity,
    String sql,
    List<Bind> binds,
    String existsSql,
    List<Bind> existsBinds) {
}
