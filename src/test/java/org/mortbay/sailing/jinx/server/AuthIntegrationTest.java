package org.mortbay.sailing.jinx.server;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;

/**
 * The server with authentication switched on, end to end and offline.
 *
 * <p>A stub issuer stands in for Google. It serves nothing but the discovery document,
 * which is all {@code OpenIdConfiguration} reads at startup — enough to prove the wiring
 * without a network or a real client secret. What is being tested is that the security
 * handler is genuinely in front of everything, not that Google works.
 */
class AuthIntegrationTest
{
    private static final ObjectMapper M = new ObjectMapper();

    private Server issuer;
    private Server jinx;

    /**
     * Who the stub issuer will say signed in, or null to refuse the client.
     *
     * <p>The id token it mints is unsigned, which is enough: Jetty's {@code JwtDecoder}
     * base64-decodes the token and {@code OpenIdCredentials} checks the issuer, audience
     * and expiry — never a signature. So a login can be completed offline, which is what
     * makes the three tiers testable at all rather than only the anonymous one.
     */
    private volatile String signingInAs;

    private String idToken(String email)
    {
        String claims = """
            {"iss":"%s","aud":"test-client","exp":%d,"iat":%d,
             "email":"%s","name":"A Sailor","hd":"myc.org.au"}"""
            .formatted("http://localhost:" + port(issuer),
                java.time.Instant.now().plusSeconds(600).getEpochSecond(),
                java.time.Instant.now().getEpochSecond(), email);
        java.util.Base64.Encoder b64 = java.util.Base64.getUrlEncoder().withoutPadding();
        return b64.encodeToString("{\"alg\":\"none\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8))
            + "." + b64.encodeToString(claims.getBytes(java.nio.charset.StandardCharsets.UTF_8))
            // A non-empty third section: JwtDecoder splits on "." and String.split drops
            // a trailing empty field, so "header.payload." arrives as two sections.
            + ".not-a-signature";
    }

    /** A browser that keeps its session cookie, which the OIDC dance requires. */
    private HttpClient browser()
    {
        return HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .cookieHandler(new java.net.CookieManager())
            .build();
    }

    /** Drive the whole sign-in, as the browser does, and leave the session signed in. */
    private void signIn(HttpClient browser, String email) throws Exception
    {
        signingInAs = email;
        String base = "http://localhost:" + port(jinx);
        HttpResponse<String> challenge = browser.send(HttpRequest.newBuilder()
                .uri(URI.create(base + JinxSecurityHandler.LOGIN_PATH)).GET().build(),
            HttpResponse.BodyHandlers.ofString());
        assertThat(challenge.statusCode(), is(303));
        String state = challenge.headers().firstValue("location").orElseThrow()
            .replaceAll(".*[?&]state=([^&]*).*", "$1");
        HttpResponse<String> cb = browser.send(HttpRequest.newBuilder()
                .uri(URI.create(base + "/auth/callback?state=" + state + "&code=a-code"))
                .GET().build(),
            HttpResponse.BodyHandlers.ofString());
        assertThat(cb.statusCode(), is(303));
    }

    private JsonNode whoami(HttpClient browser) throws Exception
    {
        return M.readTree(browser.send(HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port(jinx) + "/api/whoami")).GET().build(),
            HttpResponse.BodyHandlers.ofString()).body());
    }

    private int postAs(HttpClient browser, String path, String body) throws Exception
    {
        return browser.send(HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port(jinx) + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
            HttpResponse.BodyHandlers.ofString()).statusCode();
    }
    private final HttpClient http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build();

    @AfterEach
    void stop() throws Exception
    {
        if (jinx != null)
            jinx.stop();
        if (issuer != null)
            issuer.stop();
    }

    /** Just enough of an OpenID provider to start against. */
    private String startStubIssuer() throws Exception
    {
        issuer = new Server(0);
        ServletContextHandler ctx = new ServletContextHandler("/");
        ctx.addServlet(new ServletHolder(new jakarta.servlet.http.HttpServlet()
        {
            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse resp)
                throws java.io.IOException
            {
                String base = "http://localhost:" + port(issuer);
                resp.setContentType("application/json");
                resp.getWriter().write("""
                    {"issuer":"%s",
                     "authorization_endpoint":"%s/authorize",
                     "token_endpoint":"%s/token",
                     "jwks_uri":"%s/jwks",
                     "end_session_endpoint":"%s/logout"}
                    """.formatted(base, base, base, base, base));
            }
        }), "/.well-known/openid-configuration");
        // Google's token endpoint answers a bad client secret with 401 and a JSON body
        // naming the problem — and no WWW-Authenticate header, since it is not offering
        // a challenge. Reproduced exactly, because that combination is what breaks.
        ctx.addServlet(new ServletHolder(new jakarta.servlet.http.HttpServlet()
        {
            @Override
            protected void doPost(HttpServletRequest req, HttpServletResponse resp)
                throws java.io.IOException
            {
                resp.setContentType("application/json");
                if (signingInAs == null)
                {
                    resp.setStatus(401);
                    resp.getWriter().write(
                        "{\"error\":\"invalid_client\","
                            + "\"error_description\":\"Unauthorized\"}");
                    return;
                }
                resp.getWriter().write("""
                    {"access_token":"an-access-token","token_type":"Bearer",
                     "id_token":"%s"}""".formatted(idToken(signingInAs)));
            }
        }), "/token");
        issuer.setHandler(ctx);
        issuer.start();
        return "http://localhost:" + port(issuer);
    }

    private Server startJinx(Path dataRoot, String issuerUrl, boolean allowLoopback)
        throws Exception
    {
        Files.createDirectories(dataRoot.resolve("config"));
        Files.writeString(dataRoot.resolve("config/config.yaml"), """
            club:
              domain: "myc.org.au"
            server:
              port: 0
            """);
        Files.writeString(dataRoot.resolve("config/auth.yaml"), """
            enabled: true
            issuer: "%s"
            clientId: "test-client"
            clientSecret: "test-secret"
            allowedDomain: "myc.org.au"
            allowLoopback: %s
            admins:
              - "commodore@myc.org.au"
            """.formatted(issuerUrl, allowLoopback));
        jinx = JinxServer.start(dataRoot, 0);
        return jinx;
    }

    private static int port(Server s)
    {
        return ((ServerConnector)s.getConnectors()[0]).getLocalPort();
    }

    private HttpResponse<String> get(String path) throws Exception
    {
        return http.send(HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port(jinx) + path)).GET().build(),
            HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void anyoneMayLookAndOnlyASignInLetsThemTouch(@TempDir Path tmp) throws Exception
    {
        String issuerUrl = startStubIssuer();
        startJinx(tmp, issuerUrl, false);

        // The club's results are the point of publishing them. A visitor with no account
        // reads the fleet, the seasons and the races without being asked who they are.
        HttpResponse<String> api = get("/api/boats");
        assertThat(api.statusCode(), is(200));
        assertThat(get("/races.html").statusCode(), is(200));

        // …and cannot change any of it. 401 rather than 403: there is a sign-in that
        // would fix this, and the page needs to be able to tell the difference between
        // "log in" and "you are logged in and still may not".
        assertThat(postAs(browser(), "/api/series", "{\"name\":\"Mine Now\"}"), is(401));
    }

    @Test
    void loopbackIsAnAnonymousVisitorUnlessAllowLoopbackSaysOtherwise(@TempDir Path tmp)
        throws Exception
    {
        String issuerUrl = startStubIssuer();
        startJinx(tmp, issuerUrl, false);

        // This matters more than it looks. Every request behind the club's reverse proxy
        // arrives from 127.0.0.1, so a loopback exemption that ignores allowLoopback
        // would hand the entire internet an administrator account.
        assertThat(whoami(browser()).path("role").asText(), equalTo("VIEWER"));
        assertThat(postAs(browser(), "/api/series", "{\"name\":\"Mine Now\"}"), is(401));
    }

    @Test
    void theRedirectAsksForTheClaimsTheDomainCheckNeeds(@TempDir Path tmp) throws Exception
    {
        String issuerUrl = startStubIssuer();
        startJinx(tmp, issuerUrl, false);

        String location = get(JinxSecurityHandler.LOGIN_PATH)
            .headers().firstValue("location").orElse("");
        // Without email there is no address to check the club domain against, so the
        // scope is not cosmetic — the whole restriction rests on it.
        assertThat(location, containsString("scope="));
        assertThat(java.net.URLDecoder.decode(location, java.nio.charset.StandardCharsets.UTF_8),
            containsString("email"));
        assertThat(location, containsString("client_id=test-client"));
    }

    @Test
    void loopbackIsExemptOnlyWhenItIsAskedFor(@TempDir Path tmp) throws Exception
    {
        String issuerUrl = startStubIssuer();
        // The test client connects over loopback, so this is the exemption itself.
        startJinx(tmp, issuerUrl, true);

        HttpResponse<String> api = get("/api/boats");
        assertThat(api.statusCode(), is(200));

        // …and it is an admin, exactly as the server behaved before it had a login.
        JsonNode who = M.readTree(get("/api/whoami").body());
        assertThat(who.path("authEnabled").asBoolean(), is(true));
        assertThat(who.path("signedIn").asBoolean(), is(false));
        assertThat(who.path("admin").asBoolean(), is(true));
        assertThat(who.path("role").asText(), equalTo("ADMIN"));
    }

    @Test
    void withNoAuthFileNothingChanges(@TempDir Path tmp) throws Exception
    {
        Files.createDirectories(tmp.resolve("config"));
        Files.writeString(tmp.resolve("config/config.yaml"),
            "club:\n  domain: \"myc.org.au\"\nserver:\n  port: 0\n");
        jinx = JinxServer.start(tmp, 0);

        assertThat(get("/api/boats").statusCode(), is(200));
        JsonNode who = M.readTree(get("/api/whoami").body());
        assertThat(who.path("authEnabled").asBoolean(), is(false));
        assertThat(who.path("admin").asBoolean(), is(true));
    }

    @Test
    void aHalfConfiguredAuthFileStopsTheServer(@TempDir Path tmp) throws Exception
    {
        Files.createDirectories(tmp.resolve("config"));
        Files.writeString(tmp.resolve("config/config.yaml"),
            "club:\n  domain: \"myc.org.au\"\nserver:\n  port: 0\n");
        // Enabled, but with no client secret: the dangerous state is starting anyway.
        Files.writeString(tmp.resolve("config/auth.yaml"),
            "enabled: true\nclientId: \"test-client\"\n");

        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
            () -> JinxServer.start(tmp, 0));
    }

    @Test
    void aFailedCallbackSaysWhyInsteadOfABare403(@TempDir Path tmp) throws Exception
    {
        String issuerUrl = startStubIssuer();
        startJinx(tmp, issuerUrl, false);

        // A callback carrying a state nobody issued. The anti-forgery check rejects it —
        // but so does a stale client secret, an expired code and a session that went with
        // a restart, and all four arrive here looking identical. Jetty answers a failed
        // callback with a bare 403 unless an error page is configured, which leaves the
        // person setting the club up with a status code and no way to tell those apart.
        HttpResponse<String> cb = get("/auth/callback?state=nobody-issued-this&code=abc");
        assertThat(cb.statusCode(), is(303));
        String location = cb.headers().firstValue("location").orElse("");
        assertThat(location, containsString("/auth/error"));

        // The error page has to render for someone who is, by definition, not signed in.
        HttpResponse<String> err = http.send(HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port(jinx)).resolve(location))
                .GET().build(),
            HttpResponse.BodyHandlers.ofString());
        assertThat(err.statusCode(), is(403));
        assertThat(err.body(), containsString("invalid state parameter"));
    }

    @Test
    void aRefusedClientSecretSaysSoRatherThanBlamingTheProtocol(@TempDir Path tmp)
        throws Exception
    {
        String issuerUrl = startStubIssuer();
        startJinx(tmp, issuerUrl, false);

        // A browser: it keeps the session cookie, so the callback belongs to the sign-in
        // the challenge started and the anti-forgery check passes. Without that this test
        // would fail on the state parameter and never reach the token exchange.
        HttpClient browser = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .cookieHandler(new java.net.CookieManager())
            .build();
        String base = "http://localhost:" + port(jinx);

        HttpResponse<String> challenge = browser.send(HttpRequest.newBuilder()
            .uri(URI.create(base + JinxSecurityHandler.LOGIN_PATH)).GET().build(),
            HttpResponse.BodyHandlers.ofString());
        assertThat(challenge.statusCode(), is(303));
        String state = challenge.headers().firstValue("location").orElseThrow()
            .replaceAll(".*[?&]state=([^&]*).*", "$1");

        HttpResponse<String> cb = browser.send(HttpRequest.newBuilder()
                .uri(URI.create(base + "/auth/callback?state=" + state + "&code=an-auth-code"))
                .GET().build(),
            HttpResponse.BodyHandlers.ofString());
        assertThat(cb.statusCode(), is(303));

        HttpResponse<String> err = browser.send(HttpRequest.newBuilder()
                .uri(URI.create(base).resolve(cb.headers().firstValue("location").orElseThrow()))
                .GET().build(),
            HttpResponse.BodyHandlers.ofString());

        // The provider said which of the two ends was wrong. Jetty's HttpClient turns an
        // unchallenged 401 into a protocol violation and discards the body that says so,
        // which sends whoever is registering the club's OAuth client looking at their
        // reverse proxy instead of at their client secret.
        assertThat(err.body(), containsString("invalid_client"));
    }

    @Test
    void theRacePageLoadsWithAuthenticationOn(@TempDir Path tmp) throws Exception
    {
        String issuerUrl = startStubIssuer();
        // Loopback, so this is an admin without a sign-in — but auth is ON, which is what
        // matters: the role lookup only reads the session when there is a login to read.
        startJinx(tmp, issuerUrl, true);

        String seriesId = M.readTree(post("/api/series", "{\"name\":\"2026 Winter\"}"))
            .path("series").path("id").asText();
        String raceId = M.readTree(post("/api/races",
                "{\"seriesId\":\"" + seriesId + "\",\"date\":\"2026-06-05\"}"))
            .path("race").path("id").asText();

        // The race bundle is the one response that reports the caller's role, and the
        // only handler that asked for it without passing the request along. With the
        // login off that was invisible: the role is decided before the request is read.
        HttpResponse<String> bundle = get("/api/races/" + raceId);
        assertThat(bundle.statusCode(), is(200));
        assertThat(M.readTree(bundle.body()).path("role").asText(), equalTo("ADMIN"));
    }

    private String post(String path, String body) throws Exception
    {
        return http.send(HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port(jinx) + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
            HttpResponse.BodyHandlers.ofString()).body();
    }

    @Test
    void aSignedInClubAccountRunsRaceNightButDoesNotOwnTheSeason(@TempDir Path tmp)
        throws Exception
    {
        String issuerUrl = startStubIssuer();
        startJinx(tmp, issuerUrl, false);

        // An admin lays the season out…
        HttpClient commodore = browser();
        signIn(commodore, "commodore@myc.org.au");
        assertThat(whoami(commodore).path("role").asText(), equalTo("ADMIN"));
        String seriesId = M.readTree(postBody(commodore, "/api/series",
            "{\"name\":\"2026 Winter\"}")).path("series").path("id").asText();
        String raceId = M.readTree(postBody(commodore, "/api/races",
                "{\"seriesId\":\"" + seriesId + "\",\"date\":\"2026-06-05\"}"))
            .path("race").path("id").asText();

        // …and the race officer runs the night on it.
        HttpClient officer = browser();
        signIn(officer, "ro@myc.org.au");
        assertThat(whoami(officer).path("role").asText(), equalTo("RACE_OFFICER"));

        // Everything a race night needs, including the handicaps: processing a race is
        // what a race officer is for.
        assertThat(postAs(officer, "/api/races/" + raceId + "/times",
            "{\"times\":{}}"), is(200));

        // But not who is in the race. Registering a boat, seeding the entrants from the
        // previous race, importing them, and adding or removing one by hand are all the
        // same question — which boats this race is scored over — and it is the admin's.
        assertThat(postAs(officer, "/api/boats",
            "{\"sailNumber\":\"AUS9\",\"name\":\"Quick Silver\"}"), is(403));
        assertThat(postAs(officer, "/api/races/" + raceId + "/entrants/seed", "{}"),
            is(403));
        assertThat(postAs(officer, "/api/races/" + raceId + "/entrants/import", "{}"),
            is(403));

        // The season's shape is not. 403, not 401 — signing in is not the answer here,
        // and a page that offered a sign-in button would be lying about the fix.
        assertThat(postAs(officer, "/api/series", "{\"name\":\"2027 Winter\"}"), is(403));
        assertThat(postAs(officer, "/api/races",
            "{\"seriesId\":\"" + seriesId + "\",\"date\":\"2026-06-12\"}"), is(403));
    }

    @Test
    void signingInIsAnInvitationRatherThanAGate(@TempDir Path tmp) throws Exception
    {
        String issuerUrl = startStubIssuer();
        startJinx(tmp, issuerUrl, false);

        // Nothing demands a login any more, so there has to be a way to ask for one.
        HttpResponse<String> login = get(JinxSecurityHandler.LOGIN_PATH);
        assertThat(login.statusCode(), is(303));
        assertThat(login.headers().firstValue("location").orElse(""),
            startsWith(issuerUrl + "/authorize"));

        // And it lands back on the app rather than on the bare login path.
        HttpClient browser = browser();
        signIn(browser, "ro@myc.org.au");
        HttpResponse<String> after = browser.send(HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port(jinx) + JinxSecurityHandler.LOGIN_PATH))
                .GET().build(),
            HttpResponse.BodyHandlers.ofString());
        // 302 from sendRedirect, not the authenticator's 303: the login already happened
        // and this is the filter handing the browser back to the app.
        assertThat(after.statusCode(), is(302));
        assertThat(after.headers().firstValue("location").orElse(""), containsString("/"));
    }

    private String postBody(HttpClient browser, String path, String body) throws Exception
    {
        return browser.send(HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port(jinx) + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
            HttpResponse.BodyHandlers.ofString()).body();
    }

    @Test
    void theAuditLogNamesWhoDidIt(@TempDir Path tmp) throws Exception
    {
        String issuerUrl = startStubIssuer();
        startJinx(tmp, issuerUrl, false);

        HttpClient commodore = browser();
        signIn(commodore, "commodore@myc.org.au");
        String seriesId = M.readTree(postBody(commodore, "/api/series",
            "{\"name\":\"2026 Winter\"}")).path("series").path("id").asText();
        String raceId = M.readTree(postBody(commodore, "/api/races",
                "{\"seriesId\":\"" + seriesId + "\",\"date\":\"2026-06-05\"}"))
            .path("race").path("id").asText();

        // The race officer, not the admin who laid the season out. An audit log that
        // recorded the wrong one of those would be worse than one that recorded neither.
        HttpClient officer = browser();
        signIn(officer, "ro@myc.org.au");
        assertThat(postAs(officer, "/api/races/" + raceId + "/save-handicaps",
            "{\"adjustments\":[{\"boatId\":\"b1\",\"finishPosition\":1,"
                + "\"penaltyMinutes\":5.0,\"rewardMinutes\":0.0,"
                + "\"netAdjustmentMinutes\":5.0,\"oldTcf\":1.0,\"newTcf\":1.0526}]}"),
            is(200));

        JsonNode audit = M.readTree(commodore.send(HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port(jinx) + "/api/audit")).GET().build(),
            HttpResponse.BodyHandlers.ofString()).body());
        assertThat(audit.size(), is(1));
        assertThat(audit.get(0).path("action").asText(), equalTo("save-handicaps"));
        assertThat(audit.get(0).path("user").asText(), equalTo("ro@myc.org.au"));

        // Unlocking is the other thing that lands in the log, and it is the one somebody
        // asks about afterwards: who threw the results away.
        HttpResponse<String> del = officer.send(HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port(jinx)
                    + "/api/races/" + raceId + "/adjustments"))
                .DELETE().build(),
            HttpResponse.BodyHandlers.ofString());
        assertThat(del.statusCode(), is(200));
        JsonNode after = M.readTree(commodore.send(HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port(jinx) + "/api/audit")).GET().build(),
            HttpResponse.BodyHandlers.ofString()).body());
        assertThat(after.get(1).path("action").asText(), equalTo("unlock"));
        assertThat(after.get(1).path("user").asText(), equalTo("ro@myc.org.au"));
    }

    @Test
    void anOfficerMayEditAnEntrantButNotAddOrRemoveOne(@TempDir Path tmp) throws Exception
    {
        String issuerUrl = startStubIssuer();
        startJinx(tmp, issuerUrl, false);

        HttpClient commodore = browser();
        signIn(commodore, "commodore@myc.org.au");
        String seriesId = M.readTree(postBody(commodore, "/api/series",
            "{\"name\":\"2026 Winter\"}")).path("series").path("id").asText();
        String raceId = M.readTree(postBody(commodore, "/api/races",
                "{\"seriesId\":\"" + seriesId + "\",\"date\":\"2026-06-05\"}"))
            .path("race").path("id").asText();
        String boatId = M.readTree(postBody(commodore, "/api/boats",
            "{\"sailNumber\":\"AUS9\",\"name\":\"Quick Silver\"}"))
            .path("boat").path("id").asText();
        String oneBoat = "{\"entrants\":[{\"boatId\":\"" + boatId + "\",\"tcf\":%s}]}";
        assertThat(postAs(commodore, "/api/races/" + raceId + "/entrants",
            oneBoat.formatted("1.0")), is(200));

        HttpClient officer = browser();
        signIn(officer, "ro@myc.org.au");

        // The same boats, a different TCF: the race is still scored over the fleet the
        // admin put in it, so this is running the race rather than composing it.
        assertThat(postAs(officer, "/api/races/" + raceId + "/entrants",
            oneBoat.formatted("1.05")), is(200));

        // One boat fewer is a different race.
        assertThat(postAs(officer, "/api/races/" + raceId + "/entrants",
            "{\"entrants\":[]}"), is(403));

        // …and so is one boat more. A registered one, so the refusal is the permission
        // rather than the validation that rejects an unknown boat id for everybody.
        String second = M.readTree(postBody(commodore, "/api/boats",
            "{\"sailNumber\":\"A123\",\"name\":\"Slow Poke\"}"))
            .path("boat").path("id").asText();
        assertThat(postAs(officer, "/api/races/" + raceId + "/entrants",
            "{\"entrants\":[{\"boatId\":\"" + boatId + "\",\"tcf\":1.0},"
                + "{\"boatId\":\"" + second + "\",\"tcf\":1.0}]}"), is(403));
    }

    @Test
    void theAuditLogIsForAdminsOnly(@TempDir Path tmp) throws Exception
    {
        String issuerUrl = startStubIssuer();
        startJinx(tmp, issuerUrl, false);

        // The only GET on this server that does not answer everybody. The rest of what
        // the club holds is published — the fleet, the seasons, the results — but the
        // audit log is not a result: it is a record of who changed what, and it names
        // them. Publishing that is a different decision from publishing the racing.
        assertThat(getStatus(browser(), "/api/audit"), is(401));

        HttpClient officer = browser();
        signIn(officer, "ro@myc.org.au");
        assertThat(getStatus(officer, "/api/audit"), is(403));

        HttpClient commodore = browser();
        signIn(commodore, "commodore@myc.org.au");
        assertThat(getStatus(commodore, "/api/audit"), is(200));
    }

    private int getStatus(HttpClient browser, String path) throws Exception
    {
        return browser.send(HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port(jinx) + path)).GET().build(),
            HttpResponse.BodyHandlers.ofString()).statusCode();
    }
}
