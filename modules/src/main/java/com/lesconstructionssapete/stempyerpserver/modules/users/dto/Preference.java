package com.lesconstructionssapete.stempyerpserver.modules.users.dto;

import java.time.Instant;

import com.fasterxml.jackson.databind.JsonNode;

/** One user preference: any JSON value (never null) under a key the client chose. */
public record Preference(String key, JsonNode value, Instant updatedAt) {
}
