package com.lesconstructionssapete.stempyerpserver.modules.users.dto;

/**
 * Who is logged in, as shown to the client. Users are install-wide.
 *
 * @param language the language the user chose ("fr", "en"), or null: each
 *                 company's default applies
 */
public record UserProfile(long id, String userNo, String name, String language) {
}
