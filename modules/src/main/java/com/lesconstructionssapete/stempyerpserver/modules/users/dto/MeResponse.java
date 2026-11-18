package com.lesconstructionssapete.stempyerpserver.modules.users.dto;

import java.util.Set;

/** GET /auth/me result. */
public record MeResponse(UserProfile user, Set<String> permissions) {
}
