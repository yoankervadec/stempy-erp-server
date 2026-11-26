package com.lesconstructionssapete.stempyerpserver.platform.testsupport;

import java.math.BigDecimal;

import com.lesconstructionssapete.stempyerpserver.platform.entity.EntityDefinition;
import com.lesconstructionssapete.stempyerpserver.platform.entity.Field;
import com.lesconstructionssapete.stempyerpserver.platform.entity.Relation;
import com.lesconstructionssapete.stempyerpserver.platform.entity.StandardEntity;

/** Stock of a TestPart: a many-to-one reference, an enum and a guarded field, for the metadata tests. */
public final class TestStock extends StandardEntity {

  public enum Status {
    AVAILABLE, RESERVED
  }

  public static final EntityDefinition<TestStock> DEF = StandardEntity.define("stock", "stock", TestStock::new);

  public static final Field<TestStock, Long> PART_ID = DEF.integer("partId", "part_id", s -> s.partId,
      (s, v) -> s.partId = v).required().filterable();
  public static final Relation<TestStock, TestPart> PART_REL = DEF.manyToOne("part", () -> TestPart.DEF, "part_id");
  public static final Field<TestStock, Status> STATUS = DEF.enumeration("status", "status", Status.class,
      s -> s.status, (s, v) -> s.status = v).filterable().sortable();
  public static final Field<TestStock, BigDecimal> QUANTITY = DEF.decimal("quantity", "quantity",
      s -> s.quantity, (s, v) -> s.quantity = v).min("0").max("100000").scale(3);
  public static final Field<TestStock, Long> CURRENCY_ID = DEF.integer("currencyId", "currency_id",
      s -> s.currencyId, (s, v) -> s.currencyId = v);
  public static final Field<TestStock, BigDecimal> VALUE = DEF.decimal("value", "value", s -> s.value,
      (s, v) -> s.value = v).readPermission("stock.value.read").money(CURRENCY_ID);
  /** A part to use instead, by id only (no relation, so no join). */
  public static final Field<TestStock, Long> REPLACEMENT_ID = DEF.integer("replacementId", "replacement_id",
      s -> s.replacementId, (s, v) -> s.replacementId = v).references(() -> TestPart.DEF);

  static {
    DEF.listColumns(PART_ID, VALUE, STATUS);
  }

  public Long partId;
  public Status status;
  public BigDecimal quantity;
  public BigDecimal value;
  public Long currencyId;
  public Long replacementId;
}
