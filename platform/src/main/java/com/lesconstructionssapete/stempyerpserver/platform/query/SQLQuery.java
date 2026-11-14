package com.lesconstructionssapete.stempyerpserver.platform.query;

import java.util.List;

import com.lesconstructionssapete.stempyerpserver.platform.entity.EntityDefinition;
import com.lesconstructionssapete.stempyerpserver.platform.entity.Field;

/**
 * A translated SELECT. `selected` are the columns read from the row (includes
 * internal fields); `visible` are the fields the current user may see in the
 * response.
 *
 * @param entity   the entity definition
 * @param sql      the SQL query string
 * @param binds    the list of bind parameters
 * @param selected the list of selected fields
 * @param visible  the list of visible fields for the current user
 */
public record SQLQuery<E>(
    EntityDefinition<E> entity,
    String sql,
    List<Bind> binds,
    List<Field<E, ?>> selected,
    List<Field<E, ?>> visible) {
}
