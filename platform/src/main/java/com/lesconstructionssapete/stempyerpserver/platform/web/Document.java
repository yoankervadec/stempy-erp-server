package com.lesconstructionssapete.stempyerpserver.platform.web;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import com.lesconstructionssapete.stempyerpserver.platform.entity.EntityDefinition;
import com.lesconstructionssapete.stempyerpserver.platform.entity.Field;
import com.lesconstructionssapete.stempyerpserver.platform.entity.Relation;

/**
 * A header and its lines written whole (docs/ARCHITECTURE.md §23.8),
 * registered with DocumentEndpoints:
 *
 * <pre>
 * Document.of(SalesOrder.DEF)
 *     .collection(SalesOrder.LINES_REL)       // body key "lines"
 *     .collection(SalesOrder.PAYMENTS_REL)    // body key "payments"
 *     .rules(salesOrderService)               // DocumentRules: calculate, validate, locked...
 * </pre>
 *
 * Each collection is one of the header's one-to-many relations; its child
 * entity must have soft delete (a removed line is soft-deleted) and a
 * filterable field holding the header's id (the relation's foreign key),
 * which the server fills.
 */
public final class Document {

  /** One collection: its body key (the relation's name), child entity and the field pointing at the header. */
  public record Collection(String name, Relation<?, ?> relation, EntityDefinition<?> child, Field<?, ?> parentField) {
  }

  private final EntityDefinition<?> header;
  private final List<Collection> collections = new ArrayList<>();
  private DocumentRules rules;

  private Document(EntityDefinition<?> header) {
    this.header = Objects.requireNonNull(header, "header");
  }

  public static Document of(EntityDefinition<?> header) {
    return new Document(header);
  }

  public Document collection(Relation<?, ?> relation) {
    if (relation.kind() != Relation.Kind.ONE_TO_MANY || header.relation(relation.name()).orElse(null) != relation) {
      throw new IllegalArgumentException(header.name() + ": " + relation.name()
          + " must be one of its own one-to-many relations");
    }
    if (collections.stream().anyMatch(c -> c.name().equals(relation.name()))) {
      throw new IllegalArgumentException(header.name() + ": collection " + relation.name() + " given twice");
    }
    if (header.field(relation.name()).isPresent() || relation.name().equals("version")) {
      throw new IllegalArgumentException(header.name() + ": collection " + relation.name()
          + " has the name of a field");
    }
    EntityDefinition<?> child = relation.target();
    if (child.softDeleteColumn() == null) {
      throw new IllegalArgumentException(child.name() + " needs soft delete to be a document's lines");
    }
    Field<?, ?> parent = child.fields().stream().filter(f -> f.column().equals(relation.foreignKey()))
        .findFirst().orElseThrow(() -> new IllegalArgumentException(child.name()
            + " has no field for its foreign key " + relation.foreignKey()));
    if (!parent.isFilterable() || parent.isInternal()) {
      throw new IllegalArgumentException(child.name() + "." + parent.name() + " must be filterable (lines are read"
          + " by it)");
    }
    collections.add(new Collection(relation.name(), relation, child, parent));
    return this;
  }

  public Document rules(DocumentRules rules) {
    this.rules = Objects.requireNonNull(rules, "rules");
    return this;
  }

  public EntityDefinition<?> header() {
    return header;
  }

  public List<Collection> collections() {
    return List.copyOf(collections);
  }

  /** Null when the document has none. */
  public DocumentRules rules() {
    return rules;
  }
}
