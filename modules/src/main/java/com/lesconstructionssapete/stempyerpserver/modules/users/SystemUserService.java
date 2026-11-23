package com.lesconstructionssapete.stempyerpserver.modules.users;

import com.lesconstructionssapete.stempyerpserver.modules.users.operations.EnsureSystemUserOperation;
import com.lesconstructionssapete.stempyerpserver.platform.db.UnitOfWorkFactory;
import com.lesconstructionssapete.stempyerpserver.platform.security.SystemActor;

/**
 * The SYSTEM user, created on first use rather than by a migration, so a
 * fresh database keeps users.id = 1 for the first hand-made user. Its id never
 * changes, so it is cached after the first call.
 */
public class SystemUserService implements SystemActor {

  private final UnitOfWorkFactory uowFactory;
  private final EnsureSystemUserOperation ensureSystemUser;
  private volatile Long userId;

  public SystemUserService(UnitOfWorkFactory uowFactory, EnsureSystemUserOperation ensureSystemUser) {
    this.uowFactory = uowFactory;
    this.ensureSystemUser = ensureSystemUser;
  }

  @Override
  public long userId() {
    Long id = userId;
    if (id == null) {
      synchronized (this) {
        id = userId;
        if (id == null) {
          id = uowFactory.execute(uow -> ensureSystemUser.execute(uow, null)).id();
          userId = id;
        }
      }
    }
    return id;
  }
}
