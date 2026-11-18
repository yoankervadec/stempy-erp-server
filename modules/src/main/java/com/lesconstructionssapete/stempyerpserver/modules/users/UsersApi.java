package com.lesconstructionssapete.stempyerpserver.modules.users;

/**
 * Public API of the users module, published in the ServiceRegistry. Used by
 * the server's set-password command; later by a user administration screen.
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
}
