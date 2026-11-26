package com.lesconstructionssapete.stempyerpserver.platform.web;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import com.lesconstructionssapete.stempyerpserver.platform.entity.EntityDefinition;
import com.lesconstructionssapete.stempyerpserver.platform.entity.FieldType;

/**
 * One input of a ResourceAction (docs/ARCHITECTURE.md §23.9), described in
 * GET /meta like a field so the client builds the action's dialog: a reversal's
 * posting date, a reason. The action's route still validates what it gets.
 *
 * <pre>
 * ActionParam.date("postingDate").required()
 * ActionParam.string("description").maxLength(255)
 * ActionParam.reference("accountId", () -> Account.DEF)
 * </pre>
 */
public final class ActionParam {

  private final String name;
  private final FieldType<?> type;
  private final Supplier<? extends EntityDefinition<?>> references;
  private boolean required;
  private Integer maxLength;
  private BigDecimal min;
  private BigDecimal max;

  private ActionParam(String name, FieldType<?> type, Supplier<? extends EntityDefinition<?>> references) {
    if (name == null || !name.matches("[a-z][A-Za-z0-9]*")) {
      throw new IllegalArgumentException("Invalid action parameter name: " + name);
    }
    this.name = name;
    this.type = Objects.requireNonNull(type, "type");
    this.references = references;
  }

  public static ActionParam string(String name) {
    return new ActionParam(name, FieldType.STRING, null);
  }

  public static ActionParam integer(String name) {
    return new ActionParam(name, FieldType.LONG, null);
  }

  public static ActionParam decimal(String name) {
    return new ActionParam(name, FieldType.DECIMAL, null);
  }

  public static ActionParam bool(String name) {
    return new ActionParam(name, FieldType.BOOLEAN, null);
  }

  public static ActionParam date(String name) {
    return new ActionParam(name, FieldType.DATE, null);
  }

  public static <V extends Enum<V>> ActionParam enumeration(String name, Class<V> type) {
    return new ActionParam(name, FieldType.enumOf(type), null);
  }

  /** The id of a record of {@code target} (a picker, when the caller can read that resource). */
  public static ActionParam reference(String name, Supplier<? extends EntityDefinition<?>> target) {
    return new ActionParam(name, FieldType.LONG, Objects.requireNonNull(target, "target"));
  }

  public ActionParam required() {
    this.required = true;
    return this;
  }

  public ActionParam maxLength(int maxLength) {
    this.maxLength = maxLength;
    return this;
  }

  public ActionParam min(String min) {
    this.min = new BigDecimal(min);
    return this;
  }

  public ActionParam max(String max) {
    this.max = new BigDecimal(max);
    return this;
  }

  public String name() {
    return name;
  }

  public FieldType<?> type() {
    return type;
  }

  public Optional<EntityDefinition<?>> referencedEntity() {
    return references == null ? Optional.empty() : Optional.of(references.get());
  }

  public boolean isRequired() {
    return required;
  }

  public Integer maxLength() {
    return maxLength;
  }

  public BigDecimal min() {
    return min;
  }

  public BigDecimal max() {
    return max;
  }
}
