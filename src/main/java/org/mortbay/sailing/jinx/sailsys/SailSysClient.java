package org.mortbay.sailing.jinx.sailsys;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.eclipse.jetty.client.ContentResponse;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.Request;
import org.eclipse.jetty.client.StringRequestContent;
import org.eclipse.jetty.http.HttpMethod;
import org.mortbay.sailing.jinx.config.JinxConfig;
import org.mortbay.sailing.jinx.model.Series;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Thin client for the SailSys REST API documented in
 * {@code wiki/sailsys-api-reference.md}. Uses Jetty's {@link HttpClient} so the
 * server and client live in the same async I/O loop.
 *
 * <p>This client is <b>stateless</b>: it holds no session token. Authenticated
 * methods take a {@code sessionToken} parameter — the caller (typically
 * {@code ApiServlet}) is responsible for storing the per-browser
 * {@link SailSysSession} returned by {@link #login} in an {@code HttpSession}
 * and threading the token through subsequent calls.
 *
 * <p>This design keeps credentials short-lived. {@link #login} receives the
 * email and password as local parameters, sends them once in the request body,
 * and never assigns them to any field — they fall out of scope as soon as the
 * method returns.
 */
public class SailSysClient
{
    private static final Logger LOG = LoggerFactory.getLogger(SailSysClient.class);

    private static final String BASE = "https://api.sailsys.com.au/api/v1";

    private static final ObjectMapper MAPPER = new ObjectMapper()
        .registerModule(new JavaTimeModule());

    private final HttpClient http;
    private final JinxConfig.SailSys config;

    public SailSysClient(HttpClient http, JinxConfig.SailSys config)
    {
        this.http = http;
        this.config = config;
    }

    /**
     * POST /auth with email + password. Returns a {@link SailSysSession} the
     * caller must store on the user's browser session. Throws on any
     * non-success response so the UI can surface a clear error.
     *
     * <p>The {@code email} and {@code password} arguments are referenced only
     * during the request-build call below; this method does not retain them.
     */
    public SailSysSession login(String email, String password) throws Exception
    {
        String body = MAPPER.writeValueAsString(new LoginRequest(email, password, ""));
        // The login body carries the password — never log it. Pass a redacted
        // body to the logger; the real one still goes on the wire.
        String redacted = "{\"email\":\"" + email + "\",\"password\":\"***\",\"twoFactorAuthCode\":\"\"}";
        JsonNode root = sendAndParse(
            newRequest("/auth", "")
                .method(HttpMethod.POST)
                .body(new StringRequestContent("application/json", body)),
            "login", redacted);
        JsonNode data = root.path("data");
        String token = data.path("sessionToken").asText("");
        if (token.isEmpty())
            throw new IllegalStateException("SailSys login returned no sessionToken");

        User user = MAPPER.treeToValue(data.path("user"), User.class);
        LOG.info("SailSys login OK as {} {} ({})", user.firstname, user.surname, user.email);
        return new SailSysSession(token, user, Instant.now());
    }

    /** GET /users — useful as a "session still valid" probe. */
    public User fetchCurrentUser(String sessionToken) throws Exception
    {
        requireToken(sessionToken);
        JsonNode root = sendAndParse(
            newRequest("/users", sessionToken).method(HttpMethod.GET),
            "fetchCurrentUser", null);
        return MAPPER.treeToValue(root.path("data"), User.class);
    }

    /**
     * List all series at the given club. Endpoint and body shape captured from
     * the production HAR — the SailSys Angular app posts a paged filter:
     * <pre>
     * POST /api/v1/series/all
     * { "pageNumber": 0, "totalItemsCount": 0, "hasNextPage": true,
     *   "name": "", "clubId": "23", "states": [0, 1, 2] }
     * </pre>
     * Note that {@code clubId} is a string in the body (SailSys quirk) and
     * {@code states: [0,1,2]} asks for upcoming + active + completed.
     *
     * <p>The response envelope wraps a paged container: {@code data.items[]}.
     */
    public List<Series> fetchClubSeries(String sessionToken, int clubId) throws Exception
    {
        requireToken(sessionToken);
        String body = MAPPER.writeValueAsString(new SeriesAllRequest(
            0, 0, true, "", String.valueOf(clubId), List.of(0, 1, 2)));
        JsonNode root = sendAndParse(
            newRequest("/series/all", sessionToken)
                .method(HttpMethod.POST)
                .body(new StringRequestContent("application/json", body)),
            "fetchClubSeries", body);
        JsonNode items = root.path("data").path("items");
        List<Series> out = new ArrayList<>();
        if (items.isArray())
        {
            for (JsonNode node : items)
                out.add(MAPPER.treeToValue(node, Series.class));
        }
        return out;
    }

    /**
     * GET /series/{seriesId}/races — list races in a series. Confirmed by HAR.
     * Returns the raw {@code data} node (a bare JSON array of race summaries).
     */
    public JsonNode fetchSeriesRaces(String sessionToken, int seriesId) throws Exception
    {
        requireToken(sessionToken);
        JsonNode root = sendAndParse(
            newRequest("/series/" + seriesId + "/races", sessionToken).method(HttpMethod.GET),
            "fetchSeriesRaces", null);
        return root.path("data");
    }

    /**
     * GET /races/{raceId}/status — rich race metadata: entry counts, visibility
     * flags, processing status. Used to render the status columns on the
     * races page.
     */
    public JsonNode fetchRaceStatus(String sessionToken, int raceId) throws Exception
    {
        requireToken(sessionToken);
        JsonNode root = sendAndParse(
            newRequest("/races/" + raceId + "/status", sessionToken).method(HttpMethod.GET),
            "fetchRaceStatus", null);
        return root.path("data");
    }

    /**
     * GET /races/{raceId}/entrants — every boat entered in this race, with
     * skipper, division, and current handicap. Shown when the user expands a
     * race row on the races page.
     */
    public JsonNode fetchRaceEntrants(String sessionToken, int raceId) throws Exception
    {
        requireToken(sessionToken);
        JsonNode root = sendAndParse(
            newRequest("/races/" + raceId + "/entrants", sessionToken).method(HttpMethod.GET),
            "fetchRaceEntrants", null);
        return root.path("data");
    }

    /**
     * PUT /series/{seriesId}/entries — list series entries.
     * TODO: model the response into typed Entry records and feed JsonStore.
     */
    public JsonNode fetchSeriesEntries(String sessionToken, int seriesId) throws Exception
    {
        requireToken(sessionToken);
        String body = "{\"pageSize\":1000,\"pageNumber\":0,\"totalItemsCount\":0,"
            + "\"hasNextPage\":true,\"items\":[],\"divisionId\":null,"
            + "\"subSeriesId\":null,\"requiredStages\":null,\"handicapId\":null}";
        JsonNode root = sendAndParse(
            newRequest("/series/" + seriesId + "/entries", sessionToken)
                .method(HttpMethod.PUT)
                .body(new StringRequestContent("application/json", body)),
            "fetchSeriesEntries", body);
        return root.path("data");
    }

    /**
     * PUT /series/{seriesId}/entries/{boatId}/handicaps — write a single boat's TCF.
     * TODO: read-back verification and rate-limited batch wrapper.
     */
    public JsonNode updateHandicap(String sessionToken, int seriesId, int boatId, double value) throws Exception
    {
        requireToken(sessionToken);
        String body = MAPPER.writeValueAsString(List.of(new HandicapUpdate(
            null, config.handicapDefinitionId(), value, 3, null)));
        JsonNode root = sendAndParse(
            newRequest("/series/" + seriesId + "/entries/" + boatId + "/handicaps", sessionToken)
                .method(HttpMethod.PUT)
                .body(new StringRequestContent("application/json", body)),
            "updateHandicap", body);
        return root.path("data");
    }

    // --- internals ---

    private Request newRequest(String path, String sessionToken)
    {
        return http.newRequest(BASE + path)
            .headers(h -> {
                h.put("Content-Type", "application/json");
                h.put("app", "0");
                h.put("apptimezone", config.timezone());
                h.put("apptimezoneoffset", String.valueOf(config.timezoneOffset()));
                h.put("sessiontoken", sessionToken == null ? "" : sessionToken);
            });
    }

    private static void requireToken(String token)
    {
        if (token == null || token.isEmpty())
            throw new IllegalStateException("No SailSys session — caller must log in first");
    }

    /**
     * Sends the prepared request, logs URL/method/body/status/result-body, and
     * unwraps the standard SailSys envelope. {@code requestBodyForLog} may be
     * null (for GETs) or a redacted summary (for login).
     */
    private JsonNode sendAndParse(Request req, String op, String requestBodyForLog) throws Exception
    {
        String url = req.getURI().toString();
        String method = req.getMethod();
        LOG.debug("SailSys {} {} {}{}", op, method, url,
            requestBodyForLog == null ? "" : " body=" + requestBodyForLog);

        long t0 = System.nanoTime();
        ContentResponse resp;
        try
        {
            resp = req.send();
        }
        catch (Exception e)
        {
            LOG.warn("SailSys {} {} {} transport failure: {}", op, method, url, e.toString());
            throw e;
        }
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;

        int status = resp.getStatus();
        String body = resp.getContentAsString();
        LOG.debug("SailSys {} {} {} -> {} in {}ms ({} bytes)", op, method, url, status, elapsedMs,
            body == null ? 0 : body.length());
        LOG.debug("SailSys {} response body: {}", op, truncate(body, 4000));

        if (status != 200)
        {
            LOG.warn("SailSys {} {} {} response body: {}", op, method, url, truncate(body, 2000));
            throw new SailSysException(op, method, url, status, truncate(body, 500));
        }

        JsonNode root = MAPPER.readTree(body);
        if (!"success".equals(root.path("result").asText()))
        {
            String msg = root.path("errorMessage").asText("unknown error");
            LOG.warn("SailSys {} {} {} returned non-success envelope: {} body={}",
                op, method, url, msg, truncate(body, 2000));
            throw new SailSysException(op, method, url, status, msg);
        }
        return root;
    }

    private static String truncate(String s, int max)
    {
        if (s == null) return "";
        if (s.length() <= max) return s;
        return s.substring(0, max) + "…(" + (s.length() - max) + " more chars)";
    }

    /** Login request body. */
    private record LoginRequest(String email, String password, String twoFactorAuthCode) { }

    /** Series-list filter body for POST /series/all (HAR-derived). */
    private record SeriesAllRequest(
        int pageNumber, int totalItemsCount, boolean hasNextPage,
        String name, String clubId, List<Integer> states) { }

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

    /** Thrown for non-success responses; carries the operation tag and the URL for clearer messages. */
    public static class SailSysException extends Exception
    {
        public SailSysException(String operation, String method, String url, int httpStatus, String message)
        {
            super("SailSys " + operation + " failed (" + method + " " + url + " -> HTTP " + httpStatus + "): " + message);
        }
    }
}
