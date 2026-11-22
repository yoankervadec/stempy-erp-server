package com.lesconstructionssapete.stempyerpserver.modules.users.dto;

/** Who is logged in, as shown to the client. Users are install-wide. */
public record UserProfile(long id, String userNo, String name) {
}
