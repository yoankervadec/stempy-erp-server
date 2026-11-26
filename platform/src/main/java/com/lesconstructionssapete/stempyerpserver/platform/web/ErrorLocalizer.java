package com.lesconstructionssapete.stempyerpserver.platform.web;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.lesconstructionssapete.stempyerpserver.platform.error.FieldError;
import com.lesconstructionssapete.stempyerpserver.platform.i18n.Language;
import com.lesconstructionssapete.stempyerpserver.platform.i18n.Messages;

/**
 * Translates an error response into the request's language
 * (docs/ARCHITECTURE.md §23.5): the title from "status.NNN", the detail from
 * "error.CODE" with the exception's params, and each field error's message
 * from "error.CODE" with its own params. Whatever has no translation keeps
 * the code's English text. Codes, fields and params never change.
 *
 * Pure; ExceptionMapper calls it after ErrorTranslator.
 */
public final class ErrorLocalizer {

  private final Messages messages;

  public ErrorLocalizer(Messages messages) {
    this.messages = Objects.requireNonNull(messages, "messages");
  }

  public ApiError localize(ApiError error, Language language, Map<String, ?> params) {
    if (language == Language.EN) {
      return error;
    }
    String title = messages.translated(language, "status." + error.status(), Map.of()).orElse(error.title());
    String detail = error.detail() == null ? null
        : messages.error(language, error.code(), params).orElse(error.detail());
    List<FieldError> errors = null;
    if (error.errors() != null) {
      errors = new ArrayList<>(error.errors().size());
      for (FieldError fe : error.errors()) {
        errors.add(messages.error(language, fe.code(), fe.params()).map(fe::withMessage).orElse(fe));
      }
    }
    return new ApiError(error.type(), title, error.status(), detail, error.instance(), error.code(),
        error.requestId(), errors);
  }
}
