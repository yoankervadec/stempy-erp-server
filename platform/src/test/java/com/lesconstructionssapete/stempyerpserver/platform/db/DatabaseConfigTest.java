package com.lesconstructionssapete.stempyerpserver.platform.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.lesconstructionssapete.stempyerpserver.platform.config.Settings;

class DatabaseConfigTest {

  private static final Map<String, String> CONNECTION = Map.of(
      "DATABASE_HOST", "db", "DATABASE_PORT", "3306", "DATABASE_NAME", "erp",
      "DATABASE_USER", "u", "DATABASE_PASSWORD", "p");

  private static DatabaseConfig from(Map<String, String> env) {
    Map<String, String> all = new HashMap<>(CONNECTION);
    all.putAll(env);
    return DatabaseConfig.from(Settings.fromClasspath("db.properties").overriddenBy(Settings.of(all)));
  }

  @Test
  void connectionsRunInUtcWithTheDevelopmentTlsDefaults() {
    String url = from(Map.of()).jdbcUrl();
    assertTrue(url.startsWith("jdbc:mysql://db:3306/erp?"), url);
    assertTrue(url.contains("connectionTimeZone=UTC"), url);
    assertTrue(url.contains("forceConnectionTimeZoneToSession=true"), url);
    assertTrue(url.contains("useSSL=false"), url);
  }

  @Test
  void theEnvironmentTurnsTlsOn() {
    String url = from(Map.of("DATABASE_USE_SSL", "true", "DATABASE_ALLOW_PUBLIC_KEY_RETRIEVAL", "false",
        "DATABASE_SSL_MODE", "VERIFY_IDENTITY")).jdbcUrl();
    assertTrue(url.contains("useSSL=true"), url);
    assertTrue(url.contains("allowPublicKeyRetrieval=false"), url);
    assertTrue(url.contains("sslMode=VERIFY_IDENTITY"), url);
    assertFalse(url.contains("useSSL=false"), url);
  }

  @Test
  void theAutonomousPoolHasItsOwnSize() {
    assertEquals(4, from(Map.of()).autonomousPoolSize());
    assertEquals(2, from(Map.of("database.autonomousPool.maxSize", "2")).autonomousPoolSize());
  }
}
