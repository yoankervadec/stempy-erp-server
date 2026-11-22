package com.lesconstructionssapete.stempyerpserver.modules.users;

import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;

import com.lesconstructionssapete.stempyerpserver.modules.users.operations.ListUserCompaniesOperation;
import com.lesconstructionssapete.stempyerpserver.modules.users.operations.LoadEffectivePermissionsOperation;
import com.lesconstructionssapete.stempyerpserver.platform.db.UnitOfWorkFactory;
import com.lesconstructionssapete.stempyerpserver.platform.security.CompanyAccess;

/**
 * ORCHESTRATOR behind the platform's AuthFilter for company selection and
 * permissions. Published wrapped in a CachedCompanyAccess, so it runs on a
 * cache miss only.
 */
public class CompanyAccessService implements CompanyAccess {

  private final UnitOfWorkFactory uowFactory;
  private final LoadEffectivePermissionsOperation loadPermissions;
  private final ListUserCompaniesOperation listCompanies;

  public CompanyAccessService(UnitOfWorkFactory uowFactory, LoadEffectivePermissionsOperation loadPermissions,
      ListUserCompaniesOperation listCompanies) {
    this.uowFactory = uowFactory;
    this.loadPermissions = loadPermissions;
    this.listCompanies = listCompanies;
  }

  @Override
  public Optional<Set<String>> permissions(long userId, long companyId) {
    return uowFactory.executeReadOnly(
        uow -> loadPermissions.execute(uow, new LoadEffectivePermissionsOperation.Input(userId, companyId)));
  }

  @Override
  public OptionalLong defaultCompany(long userId) {
    Long id = uowFactory.executeReadOnly(uow -> listCompanies.execute(uow, userId)).defaultCompanyId();
    return id == null ? OptionalLong.empty() : OptionalLong.of(id);
  }
}
