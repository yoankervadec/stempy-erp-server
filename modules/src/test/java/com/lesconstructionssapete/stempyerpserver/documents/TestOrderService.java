package com.lesconstructionssapete.stempyerpserver.documents;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.Set;

import com.lesconstructionssapete.stempyerpserver.platform.db.UnitOfWork;
import com.lesconstructionssapete.stempyerpserver.platform.error.Validation;
import com.lesconstructionssapete.stempyerpserver.platform.web.DocumentRules;
import com.lesconstructionssapete.stempyerpserver.platform.web.Draft;
import com.lesconstructionssapete.stempyerpserver.platform.web.RecordRules;
import com.lesconstructionssapete.stempyerpserver.platform.web.RequestContext;

/**
 * The test order's rules: line amount = quantity x unit price, total = the
 * lines' sum; a warning above 100 units; at least one line; payments up to
 * the total; a posted order is locked.
 */
public final class TestOrderService implements DocumentRules {

  @Override
  public void calculate(UnitOfWork uow, RequestContext rc, Draft draft) {
    BigDecimal total = BigDecimal.ZERO.setScale(2);
    for (int i = 0; i < draft.size("lines"); i++) {
      BigDecimal quantity = draft.decimal("lines", i, "quantity");
      BigDecimal price = draft.decimal("lines", i, "unitPrice");
      if (quantity == null || price == null) {
        continue;
      }
      BigDecimal amount = quantity.multiply(price).setScale(2, RoundingMode.HALF_UP);
      draft.set("lines", i, "lineAmount", amount);
      total = total.add(amount);
      if (quantity.compareTo(new BigDecimal("100")) > 0) {
        draft.warn("lines[" + i + "].quantity", "LARGE_QUANTITY", "more than 100 units",
            Map.of("quantity", quantity));
      }
    }
    draft.set("total", total);
  }

  @Override
  public void validate(UnitOfWork uow, RequestContext rc, Draft draft, Validation v) {
    if (draft.size("lines") == 0) {
      v.add("lines", "TOO_FEW_LINES", "an order needs at least one line");
    }
    BigDecimal paid = BigDecimal.ZERO;
    for (int i = 0; i < draft.size("payments"); i++) {
      BigDecimal amount = draft.decimal("payments", i, "amount");
      paid = amount == null ? paid : paid.add(amount);
    }
    BigDecimal total = draft.decimal("total");
    if (total != null && paid.compareTo(total) > 0) {
      v.add("payments", "PAYMENTS_EXCEED_TOTAL", "the payments exceed the total");
    }
  }

  private static boolean posted(Map<String, Object> row) {
    return "POSTED".equals(String.valueOf(row.get("status")));
  }

  @Override
  public Set<String> locked(UnitOfWork uow, RequestContext rc, Map<String, Object> row) {
    return posted(row) ? Set.of(RecordRules.ALL) : Set.of();
  }

  @Override
  public boolean canUpdate(UnitOfWork uow, RequestContext rc, Map<String, Object> row) {
    return !posted(row);
  }

  @Override
  public boolean canDelete(UnitOfWork uow, RequestContext rc, Map<String, Object> row) {
    return !posted(row);
  }
}
