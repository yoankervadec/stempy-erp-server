package com.lesconstructionssapete.stempyerpserver.modules.users.dto;

/** Who is logged in, as shown to the client. */
public record UserProfile(long id, long companyId, String userNo, String name) {
}
