package com.lesconstructionssapete.stempyerpserver.platform.security;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PasswordHasherTest {

  private final PasswordHasher hasher = new PasswordHasher(1_000);

  @Test
  void rightPasswordVerifiesWrongOneDoesNot() {
    String stored = hasher.hash("s3cret pass".toCharArray());

    assertTrue(hasher.verify("s3cret pass".toCharArray(), stored));
    assertFalse(hasher.verify("s3cret pasS".toCharArray(), stored));
  }

  @Test
  void formatIsSelfDescribingAndSalted() {
    String a = hasher.hash("same".toCharArray());
    String b = hasher.hash("same".toCharArray());

    assertTrue(a.startsWith("pbkdf2-sha256$1000$"), a);
    assertNotEquals(a, b); // random salt
  }

  @Test
  void verifiesWithTheIterationsStoredInTheHash() {
    String old = new PasswordHasher(500).hash("pw".toCharArray());

    assertTrue(hasher.verify("pw".toCharArray(), old));
    assertTrue(hasher.needsRehash(old));
    assertFalse(hasher.needsRehash(hasher.hash("pw".toCharArray())));
  }

  @Test
  void malformedStoredValuesNeverMatch() {
    for (String bad : new String[] { null, "", "plaintext", "bcrypt$10$abc$def",
        "pbkdf2-sha256$x$AAAA$AAAA", "pbkdf2-sha256$999999999$AAAA$AAAA", "pbkdf2-sha256$1000$!!$!!" }) {
      assertFalse(hasher.verify("pw".toCharArray(), bad), String.valueOf(bad));
    }
  }

  @Test
  void productionDefaultIsTheOwaspCost() {
    assertTrue(new PasswordHasher().needsRehash(hasher.hash("pw".toCharArray())));
    assertThrows(IllegalArgumentException.class, () -> new PasswordHasher(0));
  }
}
