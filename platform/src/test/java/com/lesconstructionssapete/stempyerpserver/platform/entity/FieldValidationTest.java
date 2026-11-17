package com.lesconstructionssapete.stempyerpserver.platform.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.lesconstructionssapete.stempyerpserver.platform.error.FieldError;
import com.lesconstructionssapete.stempyerpserver.platform.testsupport.TestWidget;

class FieldValidationTest {

  @Test
  void nameLongerThanMaxLengthIsRejected() {
    List<FieldError> errors = new ArrayList<>();

    TestWidget.NAME.validate("toolong", errors); // max is 5

    assertEquals(1, errors.size());
    assertEquals("TOO_LONG", errors.get(0).code());
  }

  @Test
  void missingRequiredValueIsRejected() {
    List<FieldError> errors = new ArrayList<>();
    TestWidget.NAME.validate(null, errors);
    assertEquals("REQUIRED", errors.get(0).code());
  }

  @Test
  void validValuePasses() {
    List<FieldError> errors = new ArrayList<>();
    TestWidget.NAME.validate("ok", errors);
    assertTrue(errors.isEmpty());
  }
}
