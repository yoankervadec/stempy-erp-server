package com.lesconstructionssapete.stempyerpserver.platform.numbering;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * A kind of document that gets numbers, declared by the module that owns it in
 * {@code ERPModule.registerDocumentTypes} (docs/ARCHITECTURE.md §21.4):
 *
 * <pre>
 * types.register(DocumentType.of("sales.customer-order", "Customer orders")
 *     .withParameters("STORE_NO", "TERMINAL_NO")
 *     .withDefaultSeries("CO", "CO-{STORE_NO:3}{TERMINAL_NO:2}{SEQ:6}", Mode.GAPS_ALLOWED));
 * </pre>
 *
 * How the numbers look is data (number_series, per company); this says only
 * what callers can supply and what a company gets when it has no series yet.
 *
 * Immutable: every {@code with...} returns a copy. Each argument is checked
 * where it is given; {@link DocumentTypes#register} checks the whole.
 */
public final class DocumentType {

  /** Dotted lower-case words: {@code sales.customer-order}, {@code gl.journal-entry}. */
  public static final Pattern KEY = Pattern.compile("[a-z][a-z0-9-]*(\\.[a-z][a-z0-9-]*)+");

  /** Series codes: {@code CO}, {@code WEB}, {@code JE-2027}. */
  public static final Pattern SERIES_CODE = Pattern.compile("[A-Z0-9][A-Z0-9_-]{0,49}");

  private static final int MAX_KEY_LENGTH = 100;
  private static final int MAX_DESCRIPTION_LENGTH = 255;

  /** The series a company gets on first use when it has none for this type. */
  public record DefaultSeries(String code, String pattern, Mode mode) {

    public DefaultSeries {
      requireSeriesCode(code);
      Objects.requireNonNull(pattern, "pattern");
      Objects.requireNonNull(mode, "mode");
    }
  }

  private final String key;
  private final String description;
  private final List<String> parameters;
  private final boolean gaplessRequired;
  private final DefaultSeries defaultSeries;

  private DocumentType(String key, String description, List<String> parameters, boolean gaplessRequired,
      DefaultSeries defaultSeries) {
    this.key = key;
    this.description = description;
    this.parameters = parameters;
    this.gaplessRequired = gaplessRequired;
    this.defaultSeries = defaultSeries;
  }

  public static DocumentType of(String key, String description) {
    Objects.requireNonNull(key, "key");
    if (key.length() > MAX_KEY_LENGTH || !KEY.matcher(key).matches()) {
      throw new IllegalArgumentException("document type key '" + key
          + "' must be dotted lower-case words (e.g. sales.customer-order), at most " + MAX_KEY_LENGTH
          + " characters");
    }
    if (description == null || description.isBlank() || description.length() > MAX_DESCRIPTION_LENGTH) {
      throw new IllegalArgumentException("document type " + key + " needs a description of at most "
          + MAX_DESCRIPTION_LENGTH + " characters");
    }
    return new DocumentType(key, description, List.of(), false, null);
  }

  /** The values a caller can pass in a NumberRequest; a series' pattern may use any of them. */
  public DocumentType withParameters(String... names) {
    List<String> all = new ArrayList<>(parameters);
    all.addAll(Arrays.asList(names));
    Set<String> unique = new LinkedHashSet<>();
    for (String name : all) {
      Objects.requireNonNull(name, "parameter name");
      if (!NumberPattern.PARAMETER_NAME.matcher(name).matches()) {
        throw new IllegalArgumentException("parameter '" + name + "' of " + key
            + " must be upper case, digits and underscores, starting with a letter");
      }
      if (name.equals(NumberPattern.COUNTER)) {
        throw new IllegalArgumentException(NumberPattern.COUNTER + " is reserved for the counter (" + key + ")");
      }
      if (!unique.add(name)) {
        throw new IllegalArgumentException("parameter " + name + " of " + key + " is declared twice");
      }
    }
    return new DocumentType(key, description, List.copyOf(unique), gaplessRequired, defaultSeries);
  }

  /** Every series of this type must be GAPLESS (journal entries, invoices). */
  public DocumentType gaplessRequired() {
    return new DocumentType(key, description, parameters, true, defaultSeries);
  }

  /** The series a company gets on first use, when it has none for this type. */
  public DocumentType withDefaultSeries(String code, String pattern, Mode mode) {
    return new DocumentType(key, description, parameters, gaplessRequired, new DefaultSeries(code, pattern, mode));
  }

  /**
   * Checks the declaration as a whole: the default series' pattern against
   * the parameters, and its mode against {@link #gaplessRequired()}.
   *
   * @throws IllegalArgumentException naming the type and the problem
   */
  void validate() {
    if (defaultSeries == null) {
      return;
    }
    try {
      NumberPattern.parse(defaultSeries.pattern(), parameters);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("default series of " + key + ": " + e.getMessage(), e);
    }
    if (gaplessRequired && defaultSeries.mode() != Mode.GAPLESS) {
      throw new IllegalArgumentException("default series of " + key + " must be GAPLESS: the type requires it");
    }
  }

  static void requireSeriesCode(String code) {
    Objects.requireNonNull(code, "code");
    if (!SERIES_CODE.matcher(code).matches()) {
      throw new IllegalArgumentException("series code '" + code
          + "' must be upper-case letters, digits, '-' and '_', at most 50 characters");
    }
  }

  public String key() {
    return key;
  }

  public String description() {
    return description;
  }

  /** In declaration order. */
  public List<String> parameters() {
    return parameters;
  }

  public boolean isGaplessRequired() {
    return gaplessRequired;
  }

  /** Null when the type has none: a company must then create a series before its first number. */
  public DefaultSeries defaultSeries() {
    return defaultSeries;
  }

  @Override
  public String toString() {
    return key;
  }
}
