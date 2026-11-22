package com.lesconstructionssapete.stempyerpserver.modules.users;

/** The module's public API, delegating to the orchestrator that owns each action. */
final class UsersApiService implements UsersApi {

  private final AuthService auth;
  private final AuthorizationService authorization;

  UsersApiService(AuthService auth, AuthorizationService authorization) {
    this.auth = auth;
    this.authorization = authorization;
  }

  @Override
  public void setPassword(String userNo, char[] password) {
    auth.setPassword(userNo, password);
  }

  @Override
  public void assignRole(String userNo, String companyCode, String roleCode) {
    authorization.assignRole(userNo, companyCode, roleCode, null);
  }
}
