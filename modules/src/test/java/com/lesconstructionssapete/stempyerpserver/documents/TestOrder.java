package com.lesconstructionssapete.stempyerpserver.documents;

import java.math.BigDecimal;

import com.lesconstructionssapete.stempyerpserver.platform.entity.EntityDefinition;
import com.lesconstructionssapete.stempyerpserver.platform.entity.Field;
import com.lesconstructionssapete.stempyerpserver.platform.entity.Relation;
import com.lesconstructionssapete.stempyerpserver.platform.entity.StandardEntity;

/** A test-only document header: an order with lines and payments (table created by the test). */
public final class TestOrder extends StandardEntity {

  public enum Status {
    DRAFT, POSTED
  }

  public static final EntityDefinition<TestOrder> DEF = StandardEntity.define("testOrder", "test_orders",
      TestOrder::new);

  public static final Field<TestOrder, String> ORDER_NO = DEF.string("orderNo", "order_no", o -> o.orderNo,
      (o, v) -> o.orderNo = v).required().unique().maxLength(20).filterable().sortable();
  public static final Field<TestOrder, String> CUSTOMER = DEF.string("customer", "customer", o -> o.customer,
      (o, v) -> o.customer = v).maxLength(100).filterable();
  public static final Field<TestOrder, Status> STATUS = DEF.enumeration("status", "status", Status.class,
      o -> o.status, (o, v) -> o.status = v).filterable().defaultValue(Status.DRAFT);
  public static final Field<TestOrder, BigDecimal> TOTAL = DEF.decimal("total", "total", o -> o.total,
      (o, v) -> o.total = v).readOnly().scale(2);

  public static final Relation<TestOrder, TestOrderLine> LINES_REL = DEF.oneToMany("lines",
      () -> TestOrderLine.DEF, "order_id");
  public static final Relation<TestOrder, TestPayment> PAYMENTS_REL = DEF.oneToMany("payments",
      () -> TestPayment.DEF, "order_id");

  public String orderNo;
  public String customer;
  public Status status;
  public BigDecimal total;
}
