package com.lesconstructionssapete.stempyerpserver.modules.users.dto;

import java.util.SortedSet;

/**
 * GET /auth/me result: the user, the request's company, what they may do
 * there, and the language the server answers them in there ("fr", "en").
 */
public record MeResponse(UserProfile user, CompanySummary company, SortedSet<String> permissions, String language) {
}
