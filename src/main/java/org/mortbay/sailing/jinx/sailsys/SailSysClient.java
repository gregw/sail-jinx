package org.mortbay.sailing.jinx.sailsys;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.eclipse.jetty.client.ContentResponse;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.Request;
import org.eclipse.jetty.client.StringRequestContent;
import org.eclipse.jetty.http.HttpMethod;
import org.mortbay.sailing.jinx.config.JinxConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Thin client for the SailSys REST API documented in
 * {@code wiki/sailsys-api-reference.md}. Uses Jetty's {@link HttpClient} so the
 * server and client live in the same async I/O loop.
 *
 * <p>Authentication is a custom session token returned by {@code POST /auth}; once
 * captured it is sent in the {@code sessiontoken} header on every subsequent call.
 * There is no JWT, no cookie, no XSRF token. The token is held in memory only and
 * never persisted to disk.
 *
 * <p>This skeleton implements just enough to establish two-way connectivity:
 * login, fetch the current user, fetch series entries, and update a handicap.
 * Read-back verification and rate-limited batch writes are TODO.
 */
public class SailSysClient
{
    private static final Logger LOG = LoggerFactory.getLogger(SailSysClient.class);

    private static final String BASE = "https://api.sailsys.com.au/api/v1";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpClient http;
    private final JinxConfig.SailSys config;
    private volatile String sessionToken = "";
    private volatile User currentUser;

    public SailSysClient(HttpClient http, JinxConfig.SailSys config)
    {
        this.http = http;
        this.config = config;
    }

    /** True after a successful {@link #login}. */
    public boolean isAuthenticated()
    {
        return sessionToken != null && !sessionToken.isEmpty();
    }

    public User currentUser()
    {
        return currentUser;
    }

    /**
     * POST /auth with email + password. Captures the returned session token and
     * the authenticated user profile. Throws on any non-success response so
     * callers can surface a clear error to the UI.
     */
    public synchronized User login() throws Exception
    {
        String body = MAPPER.writeValueAsString(new LoginRequest(
            config.email(), config.password(), ""));
        ContentResponse resp = newRequest("/auth")
            .method(HttpMethod.POST)
            .body(new StringRequestContent("application/json", body))
            .send();
        JsonNode root = parseEnvelope(resp, "login");
        JsonNode data = root.path("data");
        String token = data.path("sessionToken").asText("");
        if (token.isEmpty())
            throw new IllegalStateException("SailSys login returned no sessionToken");

        sessionToken = token;
        currentUser = MAPPER.treeToValue(data.path("user"), User.class);
        LOG.info("SailSys login OK as {} {} ({})",
            currentUser.firstname, currentUser.surname, currentUser.email);
        return currentUser;
    }

    /** GET /users — useful as a "session still valid" probe. */
    public User fetchCurrentUser() throws Exception
    {
        requireAuth();
        ContentResponse resp = newRequest("/users").method(HttpMethod.GET).send();
        JsonNode root = parseEnvelope(resp, "fetchCurrentUser");
        return MAPPER.treeToValue(root.path("data"), User.class);
    }

    /**
     * PUT /series/{seriesId}/entries — list series entries.
     * TODO: model the response into typed Entry records and feed JsonStore.
     */
    public JsonNode fetchSeriesEntries(int seriesId) throws Exception
    {
        requireAuth();
        String body = "{\"pageSize\":1000,\"pageNumber\":0,\"totalItemsCount\":0,"
            + "\"hasNextPage\":true,\"items\":[],\"divisionId\":null,"
            + "\"subSeriesId\":null,\"requiredStages\":null,\"handicapId\":null}";
        ContentResponse resp = newRequest("/series/" + seriesId + "/entries")
            .method(HttpMethod.PUT)
            .body(new StringRequestContent("application/json", body))
            .send();
        return parseEnvelope(resp, "fetchSeriesEntries").path("data");
    }

    /**
     * PUT /series/{seriesId}/entries/{boatId}/handicaps — write a single boat's TCF.
     * TODO: read-back verification and rate-limited batch wrapper.
     */
    public JsonNode updateHandicap(int seriesId, int boatId, double value) throws Exception
    {
        requireAuth();
        String body = MAPPER.writeValueAsString(List.of(new HandicapUpdate(
            null, config.handicapDefinitionId(), value, 3, null)));
        ContentResponse resp = newRequest("/series/" + seriesId + "/entries/" + boatId + "/handicaps")
            .method(HttpMethod.PUT)
            .body(new StringRequestContent("application/json", body))
            .send();
        return parseEnvelope(resp, "updateHandicap").path("data");
    }

    // --- internals ---

    private Request newRequest(String path)
    {
        return http.newRequest(BASE + path)
            .headers(h -> {
                h.put("Content-Type", "application/json");
                h.put("app", "0");
                h.put("apptimezone", config.timezone());
                h.put("apptimezoneoffset", String.valueOf(config.timezoneOffset()));
                h.put("sessiontoken", sessionToken);
            });
    }

    private void requireAuth()
    {
        if (!isAuthenticated())
            throw new IllegalStateException("SailSysClient not authenticated; call login() first");
    }

    private JsonNode parseEnvelope(ContentResponse resp, String op) throws Exception
    {
        int status = resp.getStatus();
        String body = resp.getContentAsString();
        if (status != 200)
            throw new SailSysException(op, status, body);
        JsonNode root = MAPPER.readTree(body);
        if (!"success".equals(root.path("result").asText()))
        {
            String msg = root.path("errorMessage").asText("unknown error");
            throw new SailSysException(op, status, msg);
        }
        return root;
    }

    /** Login request body. */
    private record LoginRequest(String email, String password, String twoFactorAuthCode) { }

    /** Handicap update body — array element shape per sailsys-api-reference §6.2. */
    private record HandicapUpdate(
        Integer createdFromId, int definitionId, double value,
        int spinnakerType, String appliesFrom) { }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class User
    {
        public int id;
        public String firstname;
        public String surname;
        public String email;
        public boolean isSysAdmin;
        public int role;
        public List<ClubAdminPosition> clubAdminPositions;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class ClubAdminPosition
    {
        public ClubSummary club;
        public int adminLevel;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class ClubSummary
    {
        public int id;
        public String shortName;
        public String longName;
    }

    /** Thrown for non-success responses; carries the operation tag for clearer messages. */
    public static class SailSysException extends Exception
    {
        public SailSysException(String operation, int httpStatus, String message)
        {
            super("SailSys " + operation + " failed (HTTP " + httpStatus + "): " + message);
        }
    }
}
