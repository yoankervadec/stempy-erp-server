package com.lesconstructionssapete.stempyerpserver.platform.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lesconstructionssapete.stempyerpserver.platform.security.SessionAuthenticator.Principal;
import com.lesconstructionssapete.stempyerpserver.platform.web.ErrorTranslator;
import com.lesconstructionssapete.stempyerpserver.platform.web.ExceptionMapper;
import com.lesconstructionssapete.stempyerpserver.platform.web.JsonMapperFactory;
import com.lesconstructionssapete.stempyerpserver.platform.web.RequestContexts;
import com.lesconstructionssapete.stempyerpserver.platform.web.WebServer;
import com.lesconstructionssapete.stempyerpserver.platform.web.WebServerConfig;

/**
 * The filter on a real server, with a fake authenticator that knows one token.
 */
class AuthFilterTest {

  private static final ObjectMapper JSON = JsonMapperFactory.newObjectMapper();
  private final HttpClient http = HttpClient.newHttpClient();
  private final AtomicBoolean handlerRan = new AtomicBoolean();
  private WebServer server;

  @BeforeEach
  @SuppressWarnings("unused")
  void start() {
    SessionAuthenticator fake = token -> "good-token".equals(token)
        ? Optional.of(new Principal(7, 42, Set.of("x.read")))
        : Optional.empty();
    server = new WebServer(new WebServerConfig("127.0.0.1", 0),
        new ExceptionMapper(new ErrorTranslator()), new AuthFilter(fake)::handle);
    server.start(routes -> {
      routes.get("/public", ctx -> {
        handlerRan.set(true);
        ctx.result("ok");
      });
      routes.get("/private", ctx -> {
        handlerRan.set(true);
        ctx.json(RequestContexts.require(ctx));
      });
    });
  }

  @AfterEach
  @SuppressWarnings("unused")
  void stop() {
    server.stop();
  }

  private HttpResponse<String> get(String path, String authorization) throws Exception {
    HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path));
    if (authorization != null) {
      b.header(AuthFilter.HEADER, authorization);
    }
    return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
  }

  private static String code(HttpResponse<String> r) throws Exception {
    return JSON.readTree(r.body()).get("code").asText();
  }

  @Test
  void noHeaderLeavesPublicRoutesWorkingAndPrivateOnes401() throws Exception {
    assertEquals(200, get("/public", null).statusCode());
    HttpResponse<String> r = get("/private", null);
    assertEquals(401, r.statusCode());
    assertEquals("UNAUTHENTICATED", code(r));
  }

  @Test
  void validTokenBuildsTheRequestContext() throws Exception {
    HttpResponse<String> r = get("/private", "Bearer good-token");

    assertEquals(200, r.statusCode(), r.body());
    var body = JSON.readTree(r.body());
    assertEquals(7, body.get("userId").asLong());
    assertEquals(42, body.get("companyId").asLong());
  }

  @Test
  void schemeIsCaseInsensitive() throws Exception {
    assertEquals(200, get("/private", "bearer good-token").statusCode());
  }

  @Test
  void invalidTokenIs401AndTheHandlerNeverRuns() throws Exception {
    HttpResponse<String> r = get("/public", "Bearer stale-token");

    assertEquals(401, r.statusCode());
    assertEquals("SESSION_INVALID", code(r));
    assertFalse(handlerRan.get());
  }

  @Test
  void nonBearerHeaderIs401() throws Exception {
    for (String header : new String[] { "Basic dTE6cHc=", "Bearer ", "good-token" }) {
      HttpResponse<String> r = get("/private", header);
      assertEquals(401, r.statusCode(), header);
      assertEquals("AUTH_HEADER_INVALID", code(r), header);
    }
  }
}
