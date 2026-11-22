package com.lesconstructionssapete.stempyerpserver.platform.write;

import java.util.List;
import java.util.Map;

import com.lesconstructionssapete.stempyerpserver.platform.entity.EntityDefinition;
import com.lesconstructionssapete.stempyerpserver.platform.entity.Field;
import com.lesconstructionssapete.stempyerpserver.platform.query.Bind;

/**
 * A translated INSERT or UPDATE, ready to run.
 *
 * @param values      the client-supplied fields and their coerced values (for
 *                    the database checks and the change event)
 * @param existsSql   UPDATE only: run when no row changed, to tell "no such
 *                    row" (404) from "stale version" (409); null for INSERT
 * @param existsBinds binds for existsSql
 */
public record SQLWrite<E>(
    EntityDefinition<E> entity,
    String sql,
    List<Bind> binds,
    Map<Field<E, ?>, Object> values,
    String existsSql,
    List<Bind> existsBinds) {
}
