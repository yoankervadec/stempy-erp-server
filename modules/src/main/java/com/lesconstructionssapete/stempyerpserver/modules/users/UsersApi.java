package com.lesconstructionssapete.stempyerpserver.modules.users;

/**
 * Public API of the users module, published in the ServiceRegistry. Used by
 * the server's CLI commands; later by the administration screens.
 */
public interface UsersApi {

  /**
   * Sets (or replaces) the password of an existing user and ends all of that
   * user's sessions. The array is wiped afterwards.
   *
   * @throws com.lesconstructionssapete.stempyerpserver.platform.error.ResourceNotFoundException
   *         if no user has this user number
   * @throws com.lesconstructionssapete.stempyerpserver.platform.error.ValidationFailedException
   *         if the password is too short or too long
   */
  void setPassword(String userNo, char[] password);

  /**
   * Gives a user a role in a company, adding (or re-enabling) their membership
   * of it, and making it their default company if they had none. Idempotent;
   * effective on the user's next request.
   *
   * @throws com.lesconstructionssapete.stempyerpserver.platform.error.ResourceNotFoundException
   *         USER_NOT_FOUND, COMPANY_NOT_FOUND or ROLE_NOT_FOUND
   */
  void assignRole(String userNo, String companyCode, String roleCode);
}
