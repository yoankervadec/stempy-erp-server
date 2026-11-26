package com.lesconstructionssapete.stempyerpserver.platform.i18n;

import java.util.Locale;
import java.util.Optional;

/**
 * The languages the server writes its texts in (docs/ARCHITECTURE.md §23.5).
 * Stored and sent as the lowercase code: "fr", "en".
 */
public enum Language {
  FR, EN;

  /** When nothing else decides. */
  public static final Language DEFAULT = FR;

  public String code() {
    return name().toLowerCase(Locale.ROOT);
  }

  /** "fr", "FR", "fr-CA" and "fr_CA" are FR; empty for null, blank or an unsupported language. */
  public static Optional<Language> fromCode(String code) {
    if (code == null) {
      return Optional.empty();
    }
    String c = code.strip();
    int cut = indexOfAny(c, '-', '_');
    if (cut >= 0) {
      c = c.substring(0, cut);
    }
    for (Language l : values()) {
      if (l.code().equalsIgnoreCase(c)) {
        return Optional.of(l);
      }
    }
    return Optional.empty();
  }

  /**
   * The first supported language of an Accept-Language header, by quality
   * ("en-US,fr;q=0.8" is EN; "de,fr;q=0.5" is FR). A language refused with
   * q=0 is skipped.
   */
  public static Optional<Language> fromAcceptLanguage(String header) {
    if (header == null || header.isBlank()) {
      return Optional.empty();
    }
    Language best = null;
    double bestQ = 0;
    for (String part : header.split(",")) {
      String[] pieces = part.split(";");
      double q = 1;
      for (int i = 1; i < pieces.length; i++) {
        String p = pieces[i].strip();
        if (p.startsWith("q=")) {
          try {
            q = Double.parseDouble(p.substring(2));
          } catch (NumberFormatException e) {
            q = 0;
          }
        }
      }
      Optional<Language> l = fromCode(pieces[0]);
      if (l.isPresent() && q > bestQ) {
        best = l.get();
        bestQ = q;
      }
    }
    return Optional.ofNullable(best);
  }

  private static int indexOfAny(String s, char a, char b) {
    for (int i = 0; i < s.length(); i++) {
      if (s.charAt(i) == a || s.charAt(i) == b) {
        return i;
      }
    }
    return -1;
  }
}
