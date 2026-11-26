package com.lesconstructionssapete.stempyerpserver.app.config;

import java.util.HashMap;
import java.util.Map;

import com.lesconstructionssapete.stempyerpserver.platform.config.Settings;

import io.github.cdimascio.dotenv.Dotenv;
import io.github.cdimascio.dotenv.DotenvEntry;

/**
 * Where the server's settings come from: non-secret defaults in
 * application.properties (app) and db.properties (platform), overridden by
 * .env and the real environment (secrets: DATABASE_USER, DATABASE_PASSWORD...).
 */
public final class AppSettings {

  private AppSettings() {
  }

  public static Settings load() {
    Map<String, String> env = new HashMap<>();
    // .env plus the process environment; no .env is fine (a container sets real environment variables)
    for (DotenvEntry e : Dotenv.configure().ignoreIfMissing().load().entries()) {
      env.put(e.getKey(), e.getValue());
    }
    return Settings.fromClasspath("application.properties", "db.properties")
        .overriddenBy(Settings.of(env));
  }
}
