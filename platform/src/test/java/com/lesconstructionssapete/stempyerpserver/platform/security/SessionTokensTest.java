package com.lesconstructionssapete.stempyerpserver.platform.security;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

class SessionTokensTest {

  @Test
  void tokensAreUrlSafeAndUnique() {
    Set<String> seen = new HashSet<>();
    for (int i = 0; i < 1_000; i++) {
      String t = SessionTokens.newToken();
      assertEquals(43, t.length());
      assertTrue(t.matches("[A-Za-z0-9_-]+"), t);
      assertTrue(seen.add(t));
    }
  }

  @Test
  void hashIsDeterministic32Bytes() {
    String t = SessionTokens.newToken();
    assertEquals(32, SessionTokens.hash(t).length);
    assertArrayEquals(SessionTokens.hash(t), SessionTokens.hash(t));
  }
}
