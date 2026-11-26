package com.lesconstructionssapete.stempyerpserver.platform.web;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

import com.lesconstructionssapete.stempyerpserver.platform.entity.EntityDefinition;
import com.lesconstructionssapete.stempyerpserver.platform.entity.Field;
import com.lesconstructionssapete.stempyerpserver.platform.entity.FieldType;
import com.lesconstructionssapete.stempyerpserver.platform.entity.Relation;

/**
 * How one record is laid out (docs/ARCHITECTURE.md §23.7): sections of
 * header fields, then tables of child rows, then read-only totals, then tabs.
 * Registered with EntityEndpoints.layout(entity, layout) and described in
 * GET /meta; an entity without one gets a derived layout (MetaRoutes).
 *
 * <pre>
 * RecordLayout.of(SalesOrder.DEF)
 *     .section("general", 2, NUMBER, CUSTOMER_ID, ORDER_DATE, STATUS)
 *     .collection(SalesOrder.LINES_REL, c -> c
 *         .columns(SalesOrderLine.PRODUCT_ID, SalesOrderLine.QUANTITY, SalesOrderLine.LINE_AMOUNT)
 *         .footerSum(SalesOrderLine.LINE_AMOUNT)
 *         .editable())
 *     .summary(SUBTOTAL, TAX_TOTAL, TOTAL)
 *     .tab(RecordLayout.HISTORY)
 * </pre>
 *
 * Mistakes fail when the layout is built: a field of another entity, a
 * collection through a relation that isn't this entity's one-to-many, a
 * column of another child, a footer sum that isn't a decimal column.
 *
 * Labels (LabelKeys): "{resource}.section.{name}" else "section.{name}",
 * "{resource}.collection.{name}" else the child resource's plural, tabs
 * "tab.{name}". ModuleHost refuses to start when one is missing.
 */
public final class RecordLayout {

  /** The record's change history (needs audit.history.read). */
  public static final String HISTORY = "history";

  /** A group of header fields shown on {@code columns} columns (1 to 4). */
  public record Section(String name, int columns, List<Field<?, ?>> fields) {
  }

  /**
   * A table of the record's child rows.
   *
   * @param name      the relation's name: the key the rows have in a
   *                  document's body (§23.8)
   * @param editable  rows can be changed with the record (a document written
   *                  whole, §23.8), when the caller can update it
   * @param fixedRows rows can be changed, not added or removed
   */
  public record Collection(String name, Relation<?, ?> relation, List<Field<?, ?>> columns,
      List<Field<?, ?>> footerSums, boolean editable, boolean fixedRows) {
  }

  /** Configures one collection: its columns, footer sums, editability. */
  public static final class CollectionBuilder {
    private final EntityDefinition<?> child;
    private final List<Field<?, ?>> columns = new ArrayList<>();
    private final List<Field<?, ?>> sums = new ArrayList<>();
    private boolean editable;
    private boolean fixedRows;

    private CollectionBuilder(EntityDefinition<?> child) {
      this.child = child;
    }

    public CollectionBuilder columns(Field<?, ?>... fields) {
      for (Field<?, ?> f : fields) {
        columns.add(own(child, f, "collection column"));
      }
      return this;
    }

    /** A total under a decimal column. */
    public CollectionBuilder footerSum(Field<?, ?>... fields) {
      for (Field<?, ?> f : fields) {
        own(child, f, "footer sum");
        if (f.type() != FieldType.DECIMAL) {
          throw new IllegalArgumentException(child.name() + "." + f.name() + ": a footer sum needs a decimal field");
        }
        sums.add(f);
      }
      return this;
    }

    public CollectionBuilder editable() {
      this.editable = true;
      return this;
    }

    public CollectionBuilder fixedRows() {
      this.fixedRows = true;
      return this;
    }
  }

  private final EntityDefinition<?> entity;
  private final List<Section> sections = new ArrayList<>();
  private final List<Collection> collections = new ArrayList<>();
  private final List<Field<?, ?>> summary = new ArrayList<>();
  private final List<String> tabs = new ArrayList<>();

  private RecordLayout(EntityDefinition<?> entity) {
    this.entity = Objects.requireNonNull(entity, "entity");
  }

  public static RecordLayout of(EntityDefinition<?> entity) {
    return new RecordLayout(entity);
  }

  public RecordLayout section(String name, int columns, Field<?, ?>... fields) {
    requireName(name, sections.stream().map(Section::name).toList());
    if (columns < 1 || columns > 4) {
      throw new IllegalArgumentException(entity.name() + " section " + name + ": 1 to 4 columns");
    }
    if (fields.length == 0) {
      throw new IllegalArgumentException(entity.name() + " section " + name + " has no field");
    }
    List<Field<?, ?>> own = new ArrayList<>();
    for (Field<?, ?> f : fields) {
      own.add(own(entity, f, "section field"));
    }
    sections.add(new Section(name, columns, List.copyOf(own)));
    return this;
  }

  public RecordLayout collection(Relation<?, ?> relation, Consumer<CollectionBuilder> configure) {
    if (relation.kind() != Relation.Kind.ONE_TO_MANY || entity.relation(relation.name()).orElse(null) != relation) {
      throw new IllegalArgumentException(entity.name() + ": collection " + relation.name()
          + " must be one of its own one-to-many relations");
    }
    requireName(relation.name(), collections.stream().map(Collection::name).toList());
    CollectionBuilder c = new CollectionBuilder(relation.target());
    configure.accept(c);
    if (c.columns.isEmpty()) {
      throw new IllegalArgumentException(entity.name() + " collection " + relation.name() + " has no column");
    }
    if (!c.columns.containsAll(c.sums)) {
      throw new IllegalArgumentException(entity.name() + " collection " + relation.name()
          + ": footer sums must be columns");
    }
    collections.add(new Collection(relation.name(), relation, List.copyOf(c.columns), List.copyOf(c.sums),
        c.editable, c.fixedRows));
    return this;
  }

  /** Read-only totals under the collections (server-computed fields). */
  public RecordLayout summary(Field<?, ?>... fields) {
    for (Field<?, ?> f : fields) {
      summary.add(own(entity, f, "summary field"));
    }
    return this;
  }

  /** A built-in tab: HISTORY (later attachments, notes). */
  public RecordLayout tab(String name) {
    if (!List.of(HISTORY).contains(name)) {
      throw new IllegalArgumentException("Unknown tab " + name + "; built-in tabs: " + HISTORY);
    }
    requireName(name, tabs);
    tabs.add(name);
    return this;
  }

  public EntityDefinition<?> entity() {
    return entity;
  }

  public List<Section> sections() {
    return List.copyOf(sections);
  }

  public List<Collection> collections() {
    return List.copyOf(collections);
  }

  public List<Field<?, ?>> summary() {
    return List.copyOf(summary);
  }

  public List<String> tabs() {
    return List.copyOf(tabs);
  }

  private static Field<?, ?> own(EntityDefinition<?> entity, Field<?, ?> f, String what) {
    if (f == null || entity.field(f.name()).orElse(null) != f) {
      throw new IllegalArgumentException(entity.name() + ": " + what + " "
          + (f == null ? "null" : f.name()) + " is not one of its fields");
    }
    if (f.isInternal()) {
      throw new IllegalArgumentException(entity.name() + ": " + what + " " + f.name() + " is internal");
    }
    return f;
  }

  private void requireName(String name, List<String> taken) {
    if (name == null || !name.matches("[a-z][A-Za-z0-9]*")) {
      throw new IllegalArgumentException(entity.name() + ": invalid layout name " + name);
    }
    if (taken.contains(name)) {
      throw new IllegalArgumentException(entity.name() + ": " + name + " is used twice in its layout");
    }
  }
}
