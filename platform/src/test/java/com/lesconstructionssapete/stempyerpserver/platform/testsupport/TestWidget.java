package com.lesconstructionssapete.stempyerpserver.platform.testsupport;

import java.math.BigDecimal;

import com.lesconstructionssapete.stempyerpserver.platform.entity.EntityDefinition;
import com.lesconstructionssapete.stempyerpserver.platform.entity.Field;

public final class TestWidget {
  public static final EntityDefinition<TestWidget> DEF = EntityDefinition.of("widget", "widget", TestWidget::new);

  public static final Field<TestWidget, Long> ID = DEF.id("id", w -> w.id, (w, v) -> w.id = v);
  public static final Field<TestWidget, String> NAME = DEF.string("name", "name", w -> w.name, (w, v) -> w.name = v)
      .required().maxLength(5).filterable().sortable();
  public static final Field<TestWidget, BigDecimal> COST = DEF
      .decimal("cost", "unit_cost", w -> w.cost, (w, v) -> w.cost = v)
      .min("0").filterable().readPermission("widget.cost.read");

  public Long id;
  public String name;
  public BigDecimal cost;
}
