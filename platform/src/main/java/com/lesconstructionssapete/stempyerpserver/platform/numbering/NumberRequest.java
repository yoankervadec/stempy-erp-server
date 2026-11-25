package com.lesconstructionssapete.stempyerpserver.platform.numbering;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * What a caller asks {@link DocumentNumbers#next} for:
 *
 * <pre>
 * NumberRequest.of(companyId, "sales.customer-order")
 *     .param("STORE_NO", storeNo)
 *     .param("TERMINAL_NO", terminalNo)
 *     .series("WEB")          // optional: the company's default series otherwise
 * </pre>
 *
 * Pass every value you have: a series whose pattern doesn't use one ignores
 * it. Values are plain strings, checked only for their characters and width.
 *
 * @param seriesCode null for the default series
 */
public record NumberRequest(long companyId, String documentType, Map<String, String> params, String seriesCode) {

  public NumberRequest {
    Objects.requireNonNull(documentType, "documentType");
    params = Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(params, "params")));
  }

  public static NumberRequest of(long companyId, String documentType) {
    return new NumberRequest(companyId, documentType, Map.of(), null);
  }

  /** A copy with one more value; null values are kept and reported as missing. */
  public NumberRequest param(String name, String value) {
    Objects.requireNonNull(name, "name");
    Map<String, String> copy = new LinkedHashMap<>(params);
    copy.put(name, value);
    return new NumberRequest(companyId, documentType, copy, seriesCode);
  }

  /** A copy asking for a specific series instead of the default. */
  public NumberRequest series(String code) {
    return new NumberRequest(companyId, documentType, params, Objects.requireNonNull(code, "code"));
  }
}
