package com.lesconstructionssapete.stempyerpserver.documents;

import java.math.BigDecimal;

import com.lesconstructionssapete.stempyerpserver.platform.entity.EntityDefinition;
import com.lesconstructionssapete.stempyerpserver.platform.entity.Field;
import com.lesconstructionssapete.stempyerpserver.platform.entity.StandardEntity;

/** A test order's payment. */
public final class TestPayment extends StandardEntity {

  public static final EntityDefinition<TestPayment> DEF = StandardEntity.define("testPayment", "test_payments",
      TestPayment::new);

  public static final Field<TestPayment, Long> ORDER_ID = DEF.integer("orderId", "order_id", p -> p.orderId,
      (p, v) -> p.orderId = v).required().immutable().filterable();
  public static final Field<TestPayment, BigDecimal> AMOUNT = DEF.decimal("amount", "amount", p -> p.amount,
      (p, v) -> p.amount = v).required().min("0.01").scale(2);

  public Long orderId;
  public BigDecimal amount;
}
