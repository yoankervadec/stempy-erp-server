package com.lesconstructionssapete.stempyerpserver.platform.i18n;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import com.lesconstructionssapete.stempyerpserver.shared.Money;

/**
 * Every text the server writes for people, in each Language
 * (docs/ARCHITECTURE.md §23.5). Loaded once at startup from one UTF-8 YAML
 * file per bundle, every language side by side:
 *
 * <pre>
 * # i18n/gl.yaml (one per module, named after ERPModule.name(); i18n/platform.yaml for shared texts)
 * glAccount.field.manualPosting:
 *   en: Manual entries allowed
 *   fr: Écritures manuelles permises
 * error.PERIOD_CLOSED:              # error texts: French only, English is the code's own message
 *   fr: La période du {date} est fermée
 * </pre>
 *
 * A key defined twice (in one file or two) fails at startup, so a module can't
 * silently change another's text; so does an unknown language or a text that
 * isn't a string. Immutable and thread-safe once built.
 *
 * Lookups: the language's own text, then the English one. Labels fall back to
 * a humanized API name ("unitAmount" is "Unit amount"), so a missing key never
 * breaks a screen; the modules' completeness test catches it before release.
 *
 * Parameters: "{name}" in a text is replaced by the value named "name",
 * written for the language (Language.locale()): a BigDecimal as a number with
 * its own decimals ("1 234,50" / "1,234.50"), Money with its currency
 * ("1 234,50 $" / "$1,234.50"), a LocalDate in words ("26 novembre 2026" /
 * "November 26, 2026"), an enum constant as its label (enum.Type.CONSTANT),
 * anything else (product numbers, ids, counts) as is.
 */
public final class Messages {

  private static final Pattern PLACEHOLDER = Pattern.compile("\\{([A-Za-z][A-Za-z0-9]*)\\}");
  private static final YAMLMapper YAML = YAMLMapper.builder()
      .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
      .build();

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

  /** Loads i18n/{name}.yaml for each bundle name. A bundle may be missing. */
  public static Messages load(ClassLoader loader, List<String> bundles) {
    Map<Language, Map<String, String>> texts = new EnumMap<>(Language.class);
    for (Language l : Language.values()) {
      texts.put(l, new HashMap<>());
    }
    Map<String, String> origin = new HashMap<>();
    for (String bundle : bundles) {
      String path = "i18n/" + bundle + ".yaml";
      JsonNode root = read(loader, path);
      if (root == null) {
        continue;
      }
      for (Iterator<Map.Entry<String, JsonNode>> it = root.fields(); it.hasNext();) {
        var entry = it.next();
        String key = entry.getKey();
        String previous = origin.putIfAbsent(key, path);
        if (previous != null) {
          throw new IllegalStateException("Message key '" + key + "' is defined by both " + previous + " and " + path);
        }
        readTexts(path, key, entry.getValue()).forEach((l, text) -> texts.get(l).put(key, text));
      }
    }
    texts.replaceAll((l, m) -> Map.copyOf(m));
    return new Messages(texts);
  }

  private static JsonNode read(ClassLoader loader, String path) {
    try (InputStream in = loader.getResourceAsStream(path)) {
      if (in == null) {
        return null;
      }
      JsonNode root = YAML.readTree(in);
      if (root == null || root.isMissingNode() || root.isNull()) {
        return null;
      }
      if (!root.isObject()) {
        throw new IllegalStateException(path + ": expected keys, each with its texts by language");
      }
      return root;
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot read " + path + ": " + e.getMessage(), e);
    }
  }

  private static Map<Language, String> readTexts(String path, String key, JsonNode node) {
    if (!node.isObject() || node.isEmpty()) {
      throw new IllegalStateException(path + ": '" + key + "' must list its texts by language (en: ..., fr: ...)");
    }
    Map<Language, String> out = new EnumMap<>(Language.class);
    for (Iterator<Map.Entry<String, JsonNode>> it = node.fields(); it.hasNext();) {
      var e = it.next();
      Language language = Language.fromCode(e.getKey())
          .filter(l -> l.code().equals(e.getKey()))
          .orElseThrow(() -> new IllegalStateException(path + ": '" + key + "' has a text in unknown language '"
              + e.getKey() + "'"));
      if (!e.getValue().isTextual() || e.getValue().asText().isBlank()) {
        throw new IllegalStateException(path + ": '" + key + "." + e.getKey() + "' must be a non-empty text");
      }
      out.put(language, e.getValue().asText().strip());
    }
    return out;
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
   * A text with its {param} placeholders filled for the language (see the
   * class comment): the language's own text, else the English one. Empty for
   * a missing key or a parameter not given.
   */
  public Optional<String> text(Language language, String key, Map<String, ?> params) {
    return find(language, key).flatMap(template -> format(language, template, params));
  }

  /**
   * A translated error text, from "error.{code}" in the language's own texts
   * (never English: the code's message is English already). Empty when there
   * is no translation, or the template needs a parameter not given.
   */
  public Optional<String> error(Language language, String code, Map<String, ?> params) {
    return translated(language, "error." + code, params);
  }

  /**
   * A text English already has in the code (error messages, HTTP titles),
   * from the language's own texts only, with {param} placeholders filled.
   * Empty for English, a missing key, or a missing parameter.
   */
  public Optional<String> translated(Language language, String key, Map<String, ?> params) {
    if (language == Language.EN) {
      return Optional.empty();
    }
    String template = texts.get(language).get(key);
    return template == null ? Optional.empty() : format(language, template, params);
  }

  /** The texts one language has, by key (for the completeness tests). */
  public Map<String, String> all(Language language) {
    return Collections.unmodifiableMap(texts.get(language));
  }

  /** {name} replaced by params.get("name") written for the language; empty if one is missing. */
  Optional<String> format(Language language, String template, Map<String, ?> params) {
    Matcher m = PLACEHOLDER.matcher(template);
    StringBuilder out = new StringBuilder();
    while (m.find()) {
      Object value = params == null ? null : params.get(m.group(1));
      if (value == null) {
        return Optional.empty();
      }
      m.appendReplacement(out, Matcher.quoteReplacement(value(language, value)));
    }
    m.appendTail(out);
    return Optional.of(out.toString());
  }

  private String value(Language language, Object value) {
    Locale locale = language.locale();
    if (value instanceof Money money) {
      NumberFormat f = NumberFormat.getCurrencyInstance(locale);
      f.setCurrency(money.currency());
      int scale = Math.max(money.amount().scale(), 0);
      f.setMinimumFractionDigits(scale);
      f.setMaximumFractionDigits(scale);
      return f.format(money.amount());
    }
    if (value instanceof BigDecimal d) {
      NumberFormat f = NumberFormat.getNumberInstance(locale);
      int scale = Math.max(d.scale(), 0);
      f.setMinimumFractionDigits(scale);
      f.setMaximumFractionDigits(scale);
      return f.format(d);
    }
    if (value instanceof LocalDate date) {
      return DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(locale).format(date);
    }
    if (value instanceof Enum<?> constant) {
      return label(language, constant.name(), LabelKeys.enumValue(constant));
    }
    return value.toString();
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
