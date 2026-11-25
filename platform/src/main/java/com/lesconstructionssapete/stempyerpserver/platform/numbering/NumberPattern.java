package com.lesconstructionssapete.stempyerpserver.platform.numbering;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;

import com.lesconstructionssapete.stempyerpserver.platform.error.FieldError;
import com.lesconstructionssapete.stempyerpserver.platform.error.ValidationFailedException;

/**
 * The pattern of a number series (docs/ARCHITECTURE.md §21.3).
 *
 * <pre>
 * {SEQ:n}     the counter, zero-padded to n digits; grows past n instead of failing. Exactly one.
 * {PARAM:n}   a parameter of the document type, left-padded with '0' to n characters. n is required.
 * other text  literal: printable ASCII, no spaces, no braces
 *
 * INV-{SEQ:6}                              -> INV-000042
 * CO-{STORE_NO:3}{TERMINAL_NO:2}{SEQ:6}    -> CO-01203012346   (store 12, terminal 3, counter 12346)
 * </pre>
 *
 * Every parameter of the pattern also separates the counter: {@link Applied#scope()}
 * names the counter a set of values uses. Widths are fixed so that two value
 * combinations can never print the same number.
 *
 * Immutable and thread-safe. Pure: no database.
 */
public final class NumberPattern {

  /** Longest pattern text (the {@code number_series.pattern} column). */
  public static final int MAX_LENGTH = 255;

  /** Longest number at the pattern's padded widths. */
  public static final int MAX_NUMBER_LENGTH = 50;

  /** Widest token. */
  public static final int MAX_WIDTH = 20;

  /** The counter token's name. Reserved: no parameter may use it. */
  public static final String COUNTER = "SEQ";

  /** Parameter names: upper case, digits and underscores, starting with a letter. */
  public static final Pattern PARAMETER_NAME = Pattern.compile("[A-Z][A-Z0-9_]{0,49}");

  /** What a parameter value may contain (before padding). */
  private static final Pattern VALUE = Pattern.compile("[A-Za-z0-9-]+");

  /** Separates parameters in a scope; cannot appear in a name or a value. */
  private static final String SCOPE_SEPARATOR = ";";

  private sealed interface Part permits Literal, Parameter, Counter {
  }

  private record Literal(String text) implements Part {
  }

  private record Parameter(String name, int width) implements Part {
  }

  private record Counter(int width) implements Part {
  }

  private final String text;
  private final List<Part> parts;
  /** Parameter name -> width, sorted by name: the scope order. */
  private final Map<String, Integer> parameters;

  private NumberPattern(String text, List<Part> parts, Map<String, Integer> parameters) {
    this.text = text;
    this.parts = parts;
    this.parameters = parameters;
  }

  /**
   * Parses and validates a pattern.
   *
   * @param text                 the pattern, e.g. {@code CO-{STORE_NO:3}{TERMINAL_NO:2}{SEQ:6}}
   * @param declaredParameters   the parameters the document type can supply
   * @throws IllegalArgumentException with a client-safe message naming the
   *                                  problem
   */
  public static NumberPattern parse(String text, Collection<String> declaredParameters) {
    Objects.requireNonNull(declaredParameters, "declaredParameters");
    if (text == null || text.isEmpty()) {
      throw new IllegalArgumentException("pattern is empty");
    }
    if (text.length() > MAX_LENGTH) {
      throw new IllegalArgumentException("pattern is longer than " + MAX_LENGTH + " characters");
    }
    Set<String> declared = Set.copyOf(declaredParameters);

    List<Part> parts = new ArrayList<>();
    Map<String, Integer> parameters = new TreeMap<>();
    int counters = 0;
    int length = 0;
    int i = 0;
    while (i < text.length()) {
      char c = text.charAt(i);
      if (c == '{') {
        int close = text.indexOf('}', i + 1);
        int nextOpen = text.indexOf('{', i + 1);
        if (close < 0 || (nextOpen >= 0 && nextOpen < close)) {
          throw new IllegalArgumentException("'{' at position " + (i + 1) + " is not closed");
        }
        Part token = token(text.substring(i + 1, close), i + 1, declared);
        switch (token) {
          case Counter counter -> {
            counters++;
            length += counter.width();
          }
          case Parameter parameter -> {
            if (parameters.put(parameter.name(), parameter.width()) != null) {
              throw new IllegalArgumentException("parameter " + parameter.name() + " appears more than once");
            }
            length += parameter.width();
          }
          case Literal literal -> throw new AssertionError(literal);
        }
        parts.add(token);
        i = close + 1;
      } else if (c == '}') {
        throw new IllegalArgumentException("'}' at position " + (i + 1) + " has no matching '{'");
      } else {
        int end = i;
        while (end < text.length() && text.charAt(end) != '{' && text.charAt(end) != '}') {
          char ch = text.charAt(end);
          if (ch < 0x21 || ch > 0x7E) {
            throw new IllegalArgumentException("character at position " + (end + 1)
                + " is not allowed: literal text is printable ASCII with no spaces");
          }
          end++;
        }
        parts.add(new Literal(text.substring(i, end)));
        length += end - i;
        i = end;
      }
    }
    if (counters == 0) {
      throw new IllegalArgumentException("pattern has no {" + COUNTER + ":n} counter");
    }
    if (counters > 1) {
      throw new IllegalArgumentException("pattern has more than one {" + COUNTER + "} counter");
    }
    if (length > MAX_NUMBER_LENGTH) {
      throw new IllegalArgumentException("numbers would be " + length + " characters long, more than "
          + MAX_NUMBER_LENGTH);
    }
    return new NumberPattern(text, List.copyOf(parts), Collections.unmodifiableMap(parameters));
  }

  /** One {@code {NAME:n}} token, without its braces; {@code position} is 1-based, for messages. */
  private static Part token(String body, int position, Set<String> declared) {
    int colon = body.indexOf(':');
    String name = colon < 0 ? body : body.substring(0, colon);
    if (name.isEmpty()) {
      throw new IllegalArgumentException("token at position " + position + " has no name");
    }
    if (!name.equals(COUNTER) && !declared.contains(name)) {
      throw new IllegalArgumentException("unknown parameter " + safe(name) + " at position " + position
          + (declared.isEmpty() ? ": this document type has no parameters"
              : ": expected " + COUNTER + " or one of " + String.join(", ", new TreeSet<>(declared))));
    }
    if (colon < 0) {
      throw new IllegalArgumentException(name + " needs a width, e.g. {" + name + ":3}");
    }
    String widthText = body.substring(colon + 1);
    if (widthText.isEmpty() || widthText.length() > 2 || !widthText.chars().allMatch(Character::isDigit)) {
      throw new IllegalArgumentException("width of " + name + " must be a number from 1 to " + MAX_WIDTH);
    }
    int width = Integer.parseInt(widthText);
    if (width < 1 || width > MAX_WIDTH) {
      throw new IllegalArgumentException("width of " + name + " must be a number from 1 to " + MAX_WIDTH);
    }
    return name.equals(COUNTER) ? new Counter(width) : new Parameter(name, width);
  }

  /** A name from client input, cut short for an error message. */
  private static String safe(String name) {
    return name.length() > 50 ? name.substring(0, 50) + "..." : name;
  }

  /**
   * Validates the values for this pattern's parameters and pads them. Values
   * for parameters the pattern doesn't use are ignored: a caller passes every
   * value it has, whatever the series' pattern.
   *
   * @param values parameter name -> value, as plain strings
   * @throws ValidationFailedException listing every missing or bad value, as
   *                                   {@code params.NAME}
   */
  public Applied apply(Map<String, String> values) {
    Objects.requireNonNull(values, "values");
    List<FieldError> errors = new ArrayList<>();
    Map<String, String> padded = new TreeMap<>();
    parameters.forEach((name, width) -> {
      String field = "params." + name;
      String value = values.get(name) == null ? "" : values.get(name).trim();
      if (value.isEmpty()) {
        errors.add(new FieldError(field, "REQUIRED", name + " is required"));
      } else if (!VALUE.matcher(value).matches()) {
        errors.add(new FieldError(field, "INVALID_CHARACTERS",
            name + " may contain only letters, digits and '-'"));
      } else if (value.length() > width) {
        errors.add(new FieldError(field, "TOO_LONG", name + " is longer than " + width + " characters"));
      } else {
        padded.put(name, "0".repeat(width - value.length()) + value);
      }
    });
    if (!errors.isEmpty()) {
      throw new ValidationFailedException("Validation failed", errors);
    }
    return new Applied(Collections.unmodifiableMap(padded));
  }

  /** The pattern as given to {@link #parse}. */
  public String text() {
    return text;
  }

  /** The parameters the pattern uses, sorted by name. */
  public Set<String> parameters() {
    return parameters.keySet();
  }

  /** The pattern bound to one set of parameter values: one counter. */
  public final class Applied {

    private final Map<String, String> padded;
    private final String scope;

    private Applied(Map<String, String> padded) {
      this.padded = padded;
      List<String> entries = new ArrayList<>();
      padded.forEach((name, value) -> entries.add(name + "=" + value));
      this.scope = String.join(SCOPE_SEPARATOR, entries);
    }

    /**
     * The counter these values use within the series:
     * {@code STORE_NO=012;TERMINAL_NO=03} (padded values, sorted by name), or
     * {@code ""} when the pattern has no parameters.
     */
    public String scope() {
      return scope;
    }

    /**
     * The number for one counter value.
     *
     * @throws IllegalArgumentException if {@code value} is negative
     * @throws IllegalStateException    if the counter grew so far past its
     *                                  width that the number exceeds
     *                                  {@link #MAX_NUMBER_LENGTH}
     */
    public String format(long value) {
      if (value < 0) {
        throw new IllegalArgumentException("counter value must not be negative: " + value);
      }
      StringBuilder out = new StringBuilder(MAX_NUMBER_LENGTH);
      for (Part part : parts) {
        switch (part) {
          case Literal literal -> out.append(literal.text());
          case Parameter parameter -> out.append(padded.get(parameter.name()));
          case Counter counter -> {
            String digits = Long.toString(value);
            out.append("0".repeat(Math.max(0, counter.width() - digits.length()))).append(digits);
          }
        }
      }
      if (out.length() > MAX_NUMBER_LENGTH) {
        throw new IllegalStateException("number " + out + " is longer than " + MAX_NUMBER_LENGTH
            + " characters: the counter outgrew pattern " + text);
      }
      return out.toString();
    }
  }

  @Override
  public boolean equals(Object o) {
    return o instanceof NumberPattern other && text.equals(other.text);
  }

  @Override
  public int hashCode() {
    return text.hashCode();
  }

  @Override
  public String toString() {
    return text;
  }
}
