package com.lesconstructionssapete.stempyerpserver.modules.gl;

/** A gl_accounts row, as the account rules need it. */
public record StoredAccount(long id, int version, long companyId, AccountFields fields) {
}
