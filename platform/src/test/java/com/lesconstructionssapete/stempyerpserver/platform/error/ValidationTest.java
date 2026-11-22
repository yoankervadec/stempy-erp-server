package com.lesconstructionssapete.stempyerpserver.platform.error;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class ValidationTest {

  @Test
  void collectsOneErrorPerFieldAcrossFields() {
    Validation v = Validation.check()
        .required("userNo", "").maxLength("userNo", "", 0) // second check skipped
        .maxLength("name", "abcdef", 5)
        .required("password", new char[0]);

    assertEquals(List.of("REQUIRED", "TOO_LONG", "REQUIRED"), v.errors().stream().map(FieldError::code).toList());
  }

  @Test
  void validInputDoesNotThrow() {
    Validation.check()
        .required("userNo", "U1").maxLength("userNo", "U1", 50)
        .lengthBetween("password", "12345678".toCharArray(), 8, 256)
        .throwIfInvalid();
  }

  @Test
  void throwIfInvalidCarriesEveryError() {
    var e = assertThrows(ValidationFailedException.class, () -> Validation.check()
        .required("a", null)
        .lengthBetween("b", null, 8, 256)
        .throwIfInvalid());

    assertEquals(List.of("a", "b"), e.fieldErrors().stream().map(FieldError::field).toList());
    assertEquals("INVALID_LENGTH", e.fieldErrors().get(1).code());
  }

  @Test
  void intoSkipsFieldsThatAlreadyFailed() {
    List<FieldError> errors = new ArrayList<>(List.of(new FieldError("name", "REQUIRED", "is required")));

    Validation.into(errors).maxLength("name", "too long", 3);

    assertEquals(1, errors.size());
    assertTrue(Validation.check().isValid());
  }
}
