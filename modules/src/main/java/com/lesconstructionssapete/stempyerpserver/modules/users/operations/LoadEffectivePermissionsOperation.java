package com.lesconstructionssapete.stempyerpserver.modules.users.operations;

import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.lesconstructionssapete.stempyerpserver.modules.Operation;
import com.lesconstructionssapete.stempyerpserver.modules.users.AccessRepository;
import com.lesconstructionssapete.stempyerpserver.modules.users.EffectivePermissions;
import com.lesconstructionssapete.stempyerpserver.platform.db.UnitOfWork;
import com.lesconstructionssapete.stempyerpserver.platform.security.PermissionRegistry;

/**
 * A user's permissions in one company (EffectivePermissions), or empty if they
 * may not work in it. Grants naming a permission the code no longer declares
 * are ignored, with one warning per name.
 */
public class LoadEffectivePermissionsOperation
    implements Operation<LoadEffectivePermissionsOperation.Input, Optional<Set<String>>> {

  private static final Logger LOGGER = LoggerFactory.getLogger(LoadEffectivePermissionsOperation.class);

  public record Input(long userId, long companyId) {
  }

  private final AccessRepository access;
  private final PermissionRegistry registry;
  private final Set<String> warned = ConcurrentHashMap.newKeySet();

  public LoadEffectivePermissionsOperation(AccessRepository access, PermissionRegistry registry) {
    this.access = access;
    this.registry = registry;
  }

  @Override
  public Optional<Set<String>> execute(UnitOfWork uow, Input in) {
    if (!access.canAccess(uow, in.userId(), in.companyId())) {
      return Optional.empty();
    }
    var roles = access.roleGrants(uow, in.userId(), in.companyId());
    var overrides = access.overrides(uow, in.userId(), in.companyId());
    for (String stale : EffectivePermissions.undeclared(roles, overrides, registry.names())) {
      if (warned.add(stale)) {
        LOGGER.warn("Ignoring stored grant of undeclared permission '{}' (removed or renamed?)", stale);
      }
    }
    return Optional.of(EffectivePermissions.compute(roles, overrides, registry.names()));
  }
}
