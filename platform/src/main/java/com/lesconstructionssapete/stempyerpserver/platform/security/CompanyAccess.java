package com.lesconstructionssapete.stempyerpserver.platform.security;

import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;

import com.lesconstructionssapete.stempyerpserver.platform.i18n.Language;

/**
 * Which companies a user may work in, and with which permissions. Implemented
 * by the users module (which owns memberships, roles and overrides) and
 * published in the ServiceRegistry, wrapped in a CachedCompanyAccess; the
 * platform's AuthFilter only depends on this interface.
 */
public interface CompanyAccess {

  /**
   * The user's effective permissions in this company, or empty if the user may
   * not work in it (no enabled membership, or the company is disabled).
   */
  Optional<Set<String>> permissions(long userId, long companyId);

  /**
   * The company for a request that doesn't name one: the user's default company
   * if they can still work in it, else their only company. Empty when they have
   * none, or several and no valid default.
   */
  OptionalLong defaultCompany(long userId);

  /**
   * The language this user reads in this company: their own choice, else the
   * company's default (docs/ARCHITECTURE.md §23.5). Empty if neither is known.
   */
  default Optional<Language> language(long userId, long companyId) {
    return Optional.empty();
  }
}
