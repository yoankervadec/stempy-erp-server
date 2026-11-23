package com.lesconstructionssapete.stempyerpserver.platform.scheduler.schedule;

import java.util.BitSet;
import java.util.Locale;
import java.util.Map;

/**
 * Parsing of one plain cron field: a comma list of {@code *}, {@code n},
 * {@code a-b}, each with an optional {@code /step}. Special items ({@code L},
 * {@code MON#1}, {@code 5L}) are handled by CronExpression before it gets here.
 */
final class CronField {

  static final Map<String, Integer> MONTH_NAMES = Map.ofEntries(
      Map.entry("JAN", 1), Map.entry("FEB", 2), Map.entry("MAR", 3), Map.entry("APR", 4),
      Map.entry("MAY", 5), Map.entry("JUN", 6), Map.entry("JUL", 7), Map.entry("AUG", 8),
      Map.entry("SEP", 9), Map.entry("OCT", 10), Map.entry("NOV", 11), Map.entry("DEC", 12));

  /** Sunday is 0, as in standard cron. 7 is also accepted as Sunday by the caller. */
  static final Map<String, Integer> DAY_NAMES = Map.of(
      "SUN", 0, "MON", 1, "TUE", 2, "WED", 3, "THU", 4, "FRI", 5, "SAT", 6);

  private CronField() {
  }

  /** The set values of a comma list, each in [min, max]. */
  static BitSet parse(String field, String text, int min, int max, Map<String, Integer> names) {
    BitSet bits = new BitSet(max + 1);
    for (String item : text.split(",", -1)) {
      parseItem(field, item, min, max, names, bits);
    }
    return bits;
  }

  private static void parseItem(String field, String item, int min, int max, Map<String, Integer> names,
      BitSet bits) {
    if (item.isEmpty()) {
      throw error(field, "empty list item");
    }
    String range = item;
    int step = 1;
    int slash = item.indexOf('/');
    if (slash >= 0) {
      range = item.substring(0, slash);
      step = number(field, item.substring(slash + 1));
      if (step < 1 || step > max) {
        throw error(field, "step " + step + " is out of range 1-" + max);
      }
    }

    int start;
    int end;
    if (range.equals("*")) {
      start = min;
      end = max;
    } else {
      int dash = range.indexOf('-');
      if (dash >= 0) {
        start = value(field, range.substring(0, dash), min, max, names);
        end = value(field, range.substring(dash + 1), min, max, names);
        if (start > end) {
          throw error(field, "range " + range + " goes backwards");
        }
      } else {
        start = value(field, range, min, max, names);
        end = slash >= 0 ? max : start; // "5/15" means 5, 20, 35, 50
      }
    }
    for (int v = start; v <= end; v += step) {
      bits.set(v);
    }
  }

  /** One value: a number or, where the field has them, a name (case-insensitive). */
  static int value(String field, String token, int min, int max, Map<String, Integer> names) {
    Integer named = names.get(token.toUpperCase(Locale.ROOT));
    int v = named != null ? named : number(field, token);
    if (v < min || v > max) {
      throw error(field, "value " + token + " is out of range " + min + "-" + max);
    }
    return v;
  }

  private static int number(String field, String token) {
    if (token.isEmpty() || token.length() > 4 || !token.chars().allMatch(Character::isDigit)) {
      throw error(field, "'" + token + "' is not a valid value");
    }
    return Integer.parseInt(token);
  }

  static IllegalArgumentException error(String field, String message) {
    return new IllegalArgumentException(field + ": " + message);
  }
}
