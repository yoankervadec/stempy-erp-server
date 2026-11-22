package com.lesconstructionssapete.stempyerpserver.app;

import com.lesconstructionssapete.stempyerpserver.modules.users.UsersApi;
import com.lesconstructionssapete.stempyerpserver.platform.error.ErpException;

/**
 * {@code assign-role USER_NO COMPANY_CODE ROLE_CODE}: gives a user a role in a
 * company, adding their membership if needed. Bootstraps the first
 * administrator on a fresh install (role ADMINISTRATOR).
 *
 * A running server picks the change up within auth.permissions.cacheTtl (this
 * runs in another process, so it can't clear the server's cache).
 */
final class AssignRoleCommand {

  private AssignRoleCommand() {
  }

  /** Returns the process exit code. */
  static int run(UsersApi users, String userNo, String companyCode, String roleCode) {
    try {
      users.assignRole(userNo, companyCode, roleCode);
      System.out.println("Role " + roleCode + " assigned to " + userNo + " in company " + companyCode + ".");
      return 0;
    } catch (ErpException e) {
      System.err.println(e.getMessage());
      return 1;
    }
  }
}
