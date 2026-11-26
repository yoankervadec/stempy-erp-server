package com.lesconstructionssapete.stempyerpserver.platform.i18n;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Every text the server writes for people, in each Language
 * (docs/ARCHITECTURE.md §23.5). Loaded once at startup from UTF-8 bundles on
 * the classpath, one pair per bundle name:
 *
 * <pre>
 * i18n/platform.properties      English: shared labels (field.id, field.createdAt...), audit actions
 * i18n/platform_fr.properties   French: the same keys, plus error.CODE and status.NNN translations
 * i18n/gl.properties, i18n/gl_fr.properties ...   one pair per module, named after ERPModule.name()
 * </pre>
 *
 * All bundles of a language are merged; a key defined by two bundles fails at
 * startup, so a module can't silently change another's text. Immutable and
 * thread-safe once built.
 *
 * Lookups: a language's own bundles, then English. Labels fall back to a
 * humanized API name ("unitAmount" is "Unit amount"), so a missing key never
 * breaks a screen; the modules' completeness test catches it before release.
 *
 * Error texts: English messages are the code's own (often with values in
 * them), so only other languages translate them, from "error.CODE" templates
 * with {param} placeholders.
 */
public final class Messages {

  private static final Pattern PLACEHOLDER = Pattern.compile("\\{([A-Za-z][A-Za-z0-9]*)\\}");

  private final Map<Language, Map<String, String>> texts;

  private Messages(Map<Language, Map<String, String>> texts) {
    this.texts = texts;
  }

  /** No texts: every label is humanized, every error keeps its English message. */
  public static Messages empty() {
    Map<Language, Map<String, String>> texts = new EnumMap<>(Language.class);
    for (Language l : Language.values()) {
      texts.put(l, Map.of());
    }
    return new Messages(texts);
  }

  /**
   * Loads i18n/{name}.properties (English) and i18n/{name}_{code}.properties
   * (every other language) for each bundle name. A bundle may be missing.
   */
  public static Messages load(ClassLoader loader, List<String> bundles) {
    Map<Language, Map<String, String>> texts = new EnumMap<>(Language.class);
    Map<Language, Map<String, String>> origin = new EnumMap<>(Language.class);
    for (Language l : Language.values()) {
      texts.put(l, new HashMap<>());
      origin.put(l, new HashMap<>());
    }
    for (String bundle : bundles) {
      for (Language l : Language.values()) {
        String path = "i18n/" + bundle + (l == Language.EN ? "" : "_" + l.code()) + ".properties";
        read(loader, path).forEach((k, v) -> {
          String previous = origin.get(l).putIfAbsent(k, path);
          if (previous != null) {
            throw new IllegalStateException("Message key '" + k + "' is defined by both " + previous + " and " + path);
          }
          texts.get(l).put(k, v);
        });
      }
    }
    texts.replaceAll((l, m) -> Map.copyOf(m));
    return new Messages(texts);
  }

  private static Map<String, String> read(ClassLoader loader, String path) {
    try (InputStream in = loader.getResourceAsStream(path)) {
      if (in == null) {
        return Map.of();
      }
      Properties p = new Properties();
      try (Reader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
        p.load(r);
      }
      Map<String, String> out = new HashMap<>();
      p.stringPropertyNames().forEach(k -> out.put(k, p.getProperty(k).strip()));
      return out;
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot read " + path, e);
    }
  }

  /** The text for a key: the language's own, else the English one. */
  public Optional<String> find(Language language, String key) {
    String own = texts.get(language).get(key);
    if (own != null) {
      return Optional.of(own);
    }
    return Optional.ofNullable(texts.get(Language.EN).get(key));
  }

  /** The first of {@code keys} that has a text, else {@code apiName} humanized. */
  public String label(Language language, String apiName, String... keys) {
    for (String key : keys) {
      Optional<String> text = find(language, key);
      if (text.isPresent()) {
        return text.get();
      }
    }
    return humanize(apiName);
  }

  /**
   * A translated error text, from "error.{code}" in the language's own bundles
   * (never English: the code's message is English already). Empty when there
   * is no translation, or the template needs a parameter not given.
   */
  public Optional<String> error(Language language, String code, Map<String, ?> params) {
    return translated(language, "error." + code, params);
  }

  /**
   * A text English already has in the code (error messages, HTTP titles),
   * from the language's own bundles only, with {param} placeholders filled.
   * Empty for English, a missing key, or a missing parameter.
   */
  public Optional<String> translated(Language language, String key, Map<String, ?> params) {
    if (language == Language.EN) {
      return Optional.empty();
    }
    String template = texts.get(language).get(key);
    return template == null ? Optional.empty() : format(template, params);
  }

  /** The keys one language's bundles define (for the completeness tests). */
  public Map<String, String> all(Language language) {
    return Collections.unmodifiableMap(texts.get(language));
  }

  /** {name} replaced by params.get("name"); empty if one is missing. */
  static Optional<String> format(String template, Map<String, ?> params) {
    Matcher m = PLACEHOLDER.matcher(template);
    StringBuilder out = new StringBuilder();
    while (m.find()) {
      Object value = params == null ? null : params.get(m.group(1));
      if (value == null) {
        return Optional.empty();
      }
      String text = value instanceof BigDecimal d ? d.toPlainString() : value.toString();
      m.appendReplacement(out, Matcher.quoteReplacement(text));
    }
    m.appendTail(out);
    return Optional.of(out.toString());
  }

  /** "unitAmount" is "Unit amount", "isEnabled" is "Is enabled", "ASSET" is "Asset". */
  public static String humanize(String apiName) {
    Objects.requireNonNull(apiName, "apiName");
    if (apiName.isEmpty()) {
      return apiName;
    }
    StringBuilder out = new StringBuilder();
    boolean allCaps = apiName.equals(apiName.toUpperCase(Locale.ROOT));
    for (int i = 0; i < apiName.length(); i++) {
      char c = apiName.charAt(i);
      if (allCaps) {
        out.append(c == '_' ? ' ' : (i == 0 ? c : Character.toLowerCase(c)));
      } else if (i > 0 && Character.isUpperCase(c)) {
        out.append(' ').append(Character.toLowerCase(c));
      } else {
        out.append(i == 0 ? Character.toUpperCase(c) : c);
      }
    }
    return out.toString();
  }
}
