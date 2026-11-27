package com.lesconstructionssapete.stempyerpserver.modules.users.dto;

import java.util.List;

/** GET /auth/me/preferences result: all the user's preferences, by key (none: empty). */
public record PreferenceList(List<Preference> items) {
}
