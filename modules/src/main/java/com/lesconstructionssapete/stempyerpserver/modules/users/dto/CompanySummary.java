package com.lesconstructionssapete.stempyerpserver.modules.users.dto;

/** A company the user may work in, as shown to the client. */
public record CompanySummary(long id, String code, String description) {
}
