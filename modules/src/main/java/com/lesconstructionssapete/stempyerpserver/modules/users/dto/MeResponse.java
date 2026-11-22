package com.lesconstructionssapete.stempyerpserver.modules.users.dto;

import java.util.SortedSet;

/** GET /auth/me result: the user, the request's company, and what they may do there. */
public record MeResponse(UserProfile user, CompanySummary company, SortedSet<String> permissions) {
}
