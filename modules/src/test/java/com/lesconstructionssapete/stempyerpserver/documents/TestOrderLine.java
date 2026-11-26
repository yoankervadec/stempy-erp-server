package com.lesconstructionssapete.stempyerpserver.documents;

import java.math.BigDecimal;

import com.lesconstructionssapete.stempyerpserver.platform.entity.EntityDefinition;
import com.lesconstructionssapete.stempyerpserver.platform.entity.Field;
import com.lesconstructionssapete.stempyerpserver.platform.entity.Relation;
import com.lesconstructionssapete.stempyerpserver.platform.entity.StandardEntity;

/** A test order's line: quantity x unit price = line amount (computed by the server). */
public final class TestOrderLine extends StandardEntity {

  public static final EntityDefinition<TestOrderLine> DEF = StandardEntity.define("testOrderLine",
      "test_order_lines", TestOrderLine::new);

  public static final Field<TestOrderLine, Long> ORDER_ID = DEF.integer("orderId", "order_id", l -> l.orderId,
      (l, v) -> l.orderId = v).required().immutable().filterable();
  public static final Relation<TestOrderLine, TestOrder> ORDER_REL = DEF.manyToOne("order", () -> TestOrder.DEF,
      "order_id");
  public static final Field<TestOrderLine, String> DESCRIPTION = DEF.string("description", "description",
      l -> l.description, (l, v) -> l.description = v).maxLength(100);
  public static final Field<TestOrderLine, BigDecimal> QUANTITY = DEF.decimal("quantity", "quantity",
      l -> l.quantity, (l, v) -> l.quantity = v).required().min("0").scale(3);
  public static final Field<TestOrderLine, BigDecimal> UNIT_PRICE = DEF.decimal("unitPrice", "unit_price",
      l -> l.unitPrice, (l, v) -> l.unitPrice = v).required().min("0").scale(2);
  public static final Field<TestOrderLine, BigDecimal> LINE_AMOUNT = DEF.decimal("lineAmount", "line_amount",
      l -> l.lineAmount, (l, v) -> l.lineAmount = v).readOnly().scale(2);

  public Long orderId;
  public String description;
  public BigDecimal quantity;
  public BigDecimal unitPrice;
  public BigDecimal lineAmount;
}
