package com.lesconstructionssapete.stempyerpserver.platform.security;

import java.util.Collection;
import java.util.Map;

import com.lesconstructionssapete.stempyerpserver.platform.db.UnitOfWork;

/**
 * Who a user id is, for screens that name people (an audit history: "changed
 * by Jane Roy"). Implemented by the users module, which owns the users table;
 * platform code must not read that table itself.
 */
public interface UserDirectory {

  /** @param userNo the login (users.user_no) */
  record UserSummary(long id, String userNo, String name) {
  }

  /**
   * The users with these ids, disabled or deleted ones included (history must
   * still name them); ids that don't exist are simply absent.
   */
  Map<Long, UserSummary> find(UnitOfWork uow, Collection<Long> ids);
}
