package com.lesconstructionssapete.stempyerpserver.modules.users.operations;

import java.util.List;

import com.lesconstructionssapete.stempyerpserver.modules.Operation;
import com.lesconstructionssapete.stempyerpserver.modules.users.AccessRepository;
import com.lesconstructionssapete.stempyerpserver.modules.users.AccessRepository.CompanyRow;
import com.lesconstructionssapete.stempyerpserver.modules.users.dto.CompanySummary;
import com.lesconstructionssapete.stempyerpserver.platform.db.UnitOfWork;

/**
 * The companies a user may work in, and which one a request without
 * X-Company-Id uses: their default if still accessible, else their only
 * company, else none (several and no valid default: the header is required).
 * Input: the user id.
 */
public class ListUserCompaniesOperation implements Operation<Long, ListUserCompaniesOperation.UserCompanies> {

  /** @param defaultCompanyId null when there is no usable default */
  public record UserCompanies(List<CompanySummary> companies, Long defaultCompanyId) {
  }

  private final AccessRepository access;

  public ListUserCompaniesOperation(AccessRepository access) {
    this.access = access;
  }

  @Override
  public UserCompanies execute(UnitOfWork uow, Long userId) {
    List<CompanyRow> rows = access.accessibleCompanies(uow, userId);
    Long fallback = rows.stream().filter(CompanyRow::isDefault).findFirst()
        .or(() -> rows.size() == 1 ? rows.stream().findFirst() : java.util.Optional.empty())
        .map(r -> r.company().id())
        .orElse(null);
    return new UserCompanies(rows.stream().map(CompanyRow::company).toList(), fallback);
  }
}
