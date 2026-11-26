package com.lesconstructionssapete.stempyerpserver.platform.web;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Plain settings, built by the app module from its properties (same idea as
 * DatabaseConfig).
 *
 * @param corsOrigins the browser origins allowed to call the API from another
 *                    origin (CorsFilter), e.g. "app://stempy" for the desktop
 *                    client; empty: none
 */
public record WebServerConfig(String host, int port, List<String> corsOrigins) {

  public WebServerConfig {
    Objects.requireNonNull(host, "host");
    if (port < 0 || port > 65535) {
      throw new IllegalArgumentException("port must be 0-65535, got " + port);
    }
    corsOrigins = List.copyOf(Objects.requireNonNull(corsOrigins, "corsOrigins"));
    for (String origin : corsOrigins) {
      if (origin.isBlank() || origin.equals("*") || origin.endsWith("/")) {
        throw new IllegalArgumentException("CORS origin must be a full origin like https://erp.example.com"
            + " (no '*', no trailing '/'), got '" + origin + "'");
      }
    }
  }

  public WebServerConfig(String host, int port) {
    this(host, port, List.of());
  }

  /** Listens on all interfaces. */
  public static WebServerConfig ofPort(int port) {
    return new WebServerConfig("0.0.0.0", port);
  }

  /** The same, allowing these origins; {@code commaSeparated} may be blank (none). */
  public WebServerConfig withCorsOrigins(String commaSeparated) {
    List<String> origins = commaSeparated == null ? List.of()
        : Arrays.stream(commaSeparated.split(",")).map(String::strip).filter(s -> !s.isEmpty()).toList();
    return new WebServerConfig(host, port, origins);
  }
}
