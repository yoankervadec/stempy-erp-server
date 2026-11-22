package com.lesconstructionssapete.stempyerpserver.platform.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Optional;
import java.util.OptionalLong;
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
 * The filter on a real server, with fakes: "good-token" is user 7, a member of
 * companies 42 (default, permission x.read) and 43 (no permissions);
 * "multi-token" is user 8, member of 42 and 43 with no usable default.
 */
class AuthFilterTest {

  private static final ObjectMapper JSON = JsonMapperFactory.newObjectMapper();
  private final HttpClient http = HttpClient.newHttpClient();
  private final AtomicBoolean handlerRan = new AtomicBoolean();
  private WebServer server;

  @BeforeEach
  @SuppressWarnings("unused")
  void start() {
    SessionAuthenticator sessions = token -> switch (token) {
      case "good-token" -> Optional.of(new Principal(7));
      case "multi-token" -> Optional.of(new Principal(8));
      default -> Optional.empty();
    };
    CompanyAccess access = new CompanyAccess() {
      @Override
      public Optional<Set<String>> permissions(long userId, long companyId) {
        return companyId == 42 ? Optional.of(Set.of("x.read"))
            : companyId == 43 ? Optional.of(Set.of())
            : Optional.empty();
      }

      @Override
      public OptionalLong defaultCompany(long userId) {
        return userId == 7 ? OptionalLong.of(42) : OptionalLong.empty();
      }
    };
    server = new WebServer(new WebServerConfig("127.0.0.1", 0),
        new ExceptionMapper(new ErrorTranslator()), new AuthFilter(sessions, access)::handle);
    server.start(routes -> {
      routes.get("/public", ctx -> {
        handlerRan.set(true);
        ctx.result("ok");
      });
      routes.get("/private", ctx -> {
        handlerRan.set(true);
        ctx.json(RequestContexts.require(ctx));
      });
      routes.get("/user-only", ctx -> ctx.result(Long.toString(RequestContexts.requireUserId(ctx))));
    });
  }

  @AfterEach
  @SuppressWarnings("unused")
  void stop() {
    server.stop();
  }

  private HttpResponse<String> get(String path, String authorization) throws Exception {
    return get(path, authorization, null);
  }

  private HttpResponse<String> get(String path, String authorization, String company) throws Exception {
    HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path));
    if (authorization != null) {
      b.header(AuthFilter.HEADER, authorization);
    }
    if (company != null) {
      b.header(AuthFilter.COMPANY_HEADER, company);
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
    assertEquals(42, body.get("companyId").asLong()); // the default company
    assertEquals("x.read", body.get("permissions").get(0).asText());
  }

  // ---- company selection ----

  @Test
  void companyHeaderSelectsTheCompanyAndItsPermissions() throws Exception {
    var body = JSON.readTree(get("/private", "Bearer good-token", "43").body());
    assertEquals(43, body.get("companyId").asLong());
    assertEquals(0, body.get("permissions").size());
  }

  @Test
  void companyTheUserIsNotAMemberOfIs403() throws Exception {
    HttpResponse<String> r = get("/private", "Bearer good-token", "99");
    assertEquals(403, r.statusCode());
    assertEquals("COMPANY_ACCESS_DENIED", code(r));
  }

  @Test
  void malformedCompanyHeaderIs400() throws Exception {
    for (String header : new String[] { "abc", "0", "-1" }) {
      HttpResponse<String> r = get("/private", "Bearer good-token", header);
      assertEquals(400, r.statusCode(), header);
      assertEquals("INVALID_COMPANY_ID", code(r), header);
    }
  }

  @Test
  void severalCompaniesAndNoDefaultNeedTheHeader() throws Exception {
    HttpResponse<String> r = get("/private", "Bearer multi-token");
    assertEquals(400, r.statusCode());
    assertEquals("COMPANY_REQUIRED", code(r));

    assertEquals(200, get("/private", "Bearer multi-token", "43").statusCode());
  }

  @Test
  void routesThatDontNeedACompanyNeverResolveIt() throws Exception {
    // a bad company header only matters to routes that ask for the company
    assertEquals(200, get("/public", "Bearer good-token", "abc").statusCode());
    HttpResponse<String> r = get("/user-only", "Bearer multi-token");
    assertEquals(200, r.statusCode());
    assertEquals("8", r.body());
    assertEquals(401, get("/user-only", null).statusCode());
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
