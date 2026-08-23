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
                resp.setStatus(401);
                resp.setContentType("application/json");
                resp.getWriter().write(
                    "{\"error\":\"invalid_client\","
                        + "\"error_description\":\"Unauthorized\"}");
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
    void withAuthOnEveryPathIsBehindTheLogin(@TempDir Path tmp) throws Exception
    {
        String issuerUrl = startStubIssuer();
        startJinx(tmp, issuerUrl, false);

        // The API does not answer to a stranger — 303 to the provider, not data.
        HttpResponse<String> api = get("/api/boats");
        assertThat(api.statusCode(), is(303));
        assertThat(api.headers().firstValue("location").orElse(""),
            startsWith(issuerUrl + "/authorize"));

        // …and neither does the app itself. There is no shell to render before login:
        // everything this server holds belongs to the club.
        HttpResponse<String> page = get("/races.html");
        assertThat(page.statusCode(), is(303));
        assertThat(page.headers().firstValue("location").orElse(""),
            containsString("/authorize"));
    }

    @Test
    void theRedirectAsksForTheClaimsTheDomainCheckNeeds(@TempDir Path tmp) throws Exception
    {
        String issuerUrl = startStubIssuer();
        startJinx(tmp, issuerUrl, false);

        String location = get("/api/boats").headers().firstValue("location").orElse("");
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
            .uri(URI.create(base + "/")).GET().build(), HttpResponse.BodyHandlers.ofString());
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
}
