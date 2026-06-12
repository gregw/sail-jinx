package org.mortbay.sailing.jinx.server;

import java.io.IOException;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.mortbay.sailing.jinx.config.JinxConfig;
import org.mortbay.sailing.jinx.model.Adjustment;
import org.mortbay.sailing.jinx.model.Boat;
import org.mortbay.sailing.jinx.model.FinishStatus;
import org.mortbay.sailing.jinx.model.Race;
import org.mortbay.sailing.jinx.model.RaceStatus;
import org.mortbay.sailing.jinx.model.RaceTcfSnapshot;
import org.mortbay.sailing.jinx.model.RaceTimes;
import org.mortbay.sailing.jinx.model.Result;
import org.mortbay.sailing.jinx.pursuit.HandicapEngine;
import org.mortbay.sailing.jinx.pursuit.PursuitHandicapEngine;
import org.mortbay.sailing.jinx.pursuit.SolarTimes;
import org.mortbay.sailing.jinx.sailsys.SailSysClient;
import org.mortbay.sailing.jinx.sailsys.SailSysSession;
import org.mortbay.sailing.jinx.store.JsonStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * REST API consumed by the static HTML front end. Endpoint surface is the one
 * documented in {@code CLAUDE.md}.
 *
 * <p>Authentication is per browser session, backed by a Jetty
 * {@link HttpSession}. The flow is:
 * <ol>
 *   <li>User loads {@code index.html}, sees {@code /api/auth/status}
 *       reporting "not signed in" plus the configured club/series.</li>
 *   <li>User submits email + password to {@code POST /api/auth/login}.</li>
 *   <li>Servlet calls {@code SailSysClient.login(email, password)}, receives a
 *       {@link SailSysSession}, stashes it on the {@code HttpSession}, and
 *       returns the resolved user. The credentials live only as parameters of
 *       this request and are not stored anywhere.</li>
 *   <li>Subsequent API calls read the session and pass its token to the
 *       SailSys client.</li>
 *   <li>{@code POST /api/auth/logout} invalidates the {@code HttpSession},
 *       dropping the token. (The SailSys-side token is left to expire.)</li>
 * </ol>
 *
 * <p>Most handlers are TODOs. The auth and read-only stub endpoints are wired.
 */
public class ApiServlet extends HttpServlet
{
    private static final Logger LOG = LoggerFactory.getLogger(ApiServlet.class);

    /** Attribute name under which the {@link SailSysSession} is stored on the HttpSession. */
    static final String SESSION_ATTR = "sailsys.session";

    private static final java.util.regex.Pattern SERIES_RACES =
        java.util.regex.Pattern.compile("/series/(\\d+)/races");
    private static final java.util.regex.Pattern SERIES_TCFS =
        java.util.regex.Pattern.compile("/series/(\\d+)/tcfs");
    private static final java.util.regex.Pattern SERIES_CONFIG =
        java.util.regex.Pattern.compile("/series/(\\d+)/config");
    private static final java.util.regex.Pattern SERIES_IS_PURSUIT =
        java.util.regex.Pattern.compile("/series/(\\d+)/is-pursuit");
    private static final java.util.regex.Pattern SERIES_SPINNAKER =
        java.util.regex.Pattern.compile("/series/(\\d+)/spinnaker");
    private static final java.util.regex.Pattern RACE_ENTRANTS =
        java.util.regex.Pattern.compile("/races/(\\d+)/entrants");
    private static final java.util.regex.Pattern RACE_STATUS =
        java.util.regex.Pattern.compile("/races/(\\d+)/status");
    private static final java.util.regex.Pattern RACE_TIMES =
        java.util.regex.Pattern.compile("/races/([^/]+)/times");
    private static final java.util.regex.Pattern RACE_ENTRANTS_VIS =
        java.util.regex.Pattern.compile("/races/(\\d+)/entrants-visibility");
    private static final java.util.regex.Pattern RACE_STARTTIMES_VIS =
        java.util.regex.Pattern.compile("/races/(\\d+)/start-times-visibility");
    private static final java.util.regex.Pattern RACE_PROCESS =
        java.util.regex.Pattern.compile("/races/(\\d+)/process");
    private static final java.util.regex.Pattern RACE_PUSH_RESULTS =
        java.util.regex.Pattern.compile("/races/(\\d+)/push-results");
    private static final java.util.regex.Pattern RACE_COMPUTED_RESULTS =
        java.util.regex.Pattern.compile("/races/(\\d+)/computed-results");
    private static final java.util.regex.Pattern RACE_FINISHERS =
        java.util.regex.Pattern.compile("/races/(\\d+)/finishers");
    private static final java.util.regex.Pattern RACE_PROCESS_RESULTS =
        java.util.regex.Pattern.compile("/races/(\\d+)/process-results");
    private static final java.util.regex.Pattern RACE_RESULTS_STATUS =
        java.util.regex.Pattern.compile("/races/(\\d+)/results-status");
    private static final java.util.regex.Pattern RACE_DIVISION_STARTS =
        java.util.regex.Pattern.compile("/races/(\\d+)/division-starts");
    private static final java.util.regex.Pattern RACE_ABANDON =
        java.util.regex.Pattern.compile("/races/(\\d+)/abandon");
    private static final java.util.regex.Pattern RACE_CALIBRATE =
        java.util.regex.Pattern.compile("/races/(\\d+)/calibrate");
    private static final java.util.regex.Pattern RACE_COURSE_PLAN =
        java.util.regex.Pattern.compile("/races/(\\d+)/course-plan");
    private static final java.util.regex.Pattern RACE_PROCESS_HANDICAPS =
        java.util.regex.Pattern.compile("/races/(\\d+)/process-handicaps");
    private static final java.util.regex.Pattern RACE_SAVE_HANDICAPS =
        java.util.regex.Pattern.compile("/races/(\\d+)/save-handicaps");
    private static final java.util.regex.Pattern RACE_PENDING_HANDICAPS =
        java.util.regex.Pattern.compile("/races/(\\d+)/pending-handicaps");
    private static final java.util.regex.Pattern RACE_TCF_SNAPSHOT =
        java.util.regex.Pattern.compile("/races/(\\d+)/tcf-snapshot");
    private static final java.util.regex.Pattern RACE_SAVE_TCFS =
        java.util.regex.Pattern.compile("/races/(\\d+)/save-tcfs");
    private static final java.util.regex.Pattern RACE_PUSH_HANDICAPS =
        java.util.regex.Pattern.compile("/races/(\\d+)/push-handicaps");

    private static final JsonMapper MAPPER = JsonMapper.builder()
        .addModule(new JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        .build();

    private final JinxConfig config;
    private final JsonStore store;
    private final SailSysClient sailsys;
    private final HandicapEngine engine;

    // In-memory cache of the SailSys handicap-definition catalogue, keyed by
    // clubId. The catalogue is club-wide (the per-series endpoint returns the
    // same data for any series at the same club), so the first request
    // populates the cache and every subsequent reader — handleSeries, the
    // new handleGetHandicapDefinitions, etc. — reads from it.
    private final java.util.Map<Integer, Map<String, Map<String, String>>> handicapDefsCache
        = new java.util.concurrent.ConcurrentHashMap<>();

    // In-memory cache of per-series pursuit determination, keyed by seriesId.
    // A series's race type is fixed (Twilight = all pursuit, Championship = all
    // scratch), so the first probe settles it; every later series-page load
    // reads the cached answer instead of re-hitting SailSys.
    private final java.util.Map<Integer, Boolean> pursuitCache
        = new java.util.concurrent.ConcurrentHashMap<>();

    public ApiServlet(JinxConfig config, JsonStore store, SailSysClient sailsys, HandicapEngine engine)
    {
        this.config = config;
        this.store = store;
        this.sailsys = sailsys;
        this.engine = engine;
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException
    {
        String path = req.getPathInfo();
        if (path == null) path = "/";
        resp.setContentType("application/json");

        try
        {
            switch (path)
            {
                case "/auth/status" -> writeJson(resp, authStatus(req));
                case "/config" -> writeJson(resp, publicConfig());
                case "/series" -> handleSeries(req, resp);
                case "/handicap-definitions" -> handleGetHandicapDefinitions(req, resp);
                case "/races/current" -> handleCurrentRaces(req, resp);
                case "/boats" -> writeJson(resp, store.boats().values());
                case "/races" -> writeJson(resp, store.races().values());
                case "/audit" -> writeJson(resp, store.audit());
                case "/calibration" -> handleGetCalibration(resp);
                default -> {
                    java.util.regex.Matcher sr = SERIES_RACES.matcher(path);
                    java.util.regex.Matcher sc = SERIES_CONFIG.matcher(path);
                    java.util.regex.Matcher re = RACE_ENTRANTS.matcher(path);
                    java.util.regex.Matcher rs = RACE_STATUS.matcher(path);
                    java.util.regex.Matcher rt = RACE_TIMES.matcher(path);
                    if (sr.matches())
                        handleSeriesRaces(req, resp, Integer.parseInt(sr.group(1)));
                    else if (sc.matches())
                        handleGetSeriesConfig(resp, sc.group(1));
                    else if (SERIES_IS_PURSUIT.matcher(path).matches())
                    {
                        java.util.regex.Matcher m = SERIES_IS_PURSUIT.matcher(path);
                        if (m.matches())
                            handleIsSeriesPursuit(req, resp, Integer.parseInt(m.group(1)));
                    }
                    else if (SERIES_SPINNAKER.matcher(path).matches())
                    {
                        java.util.regex.Matcher m = SERIES_SPINNAKER.matcher(path);
                        if (m.matches())
                            handleSeriesSpinnaker(req, resp, Integer.parseInt(m.group(1)));
                    }
                    else if (re.matches())
                        handleRaceEntrants(req, resp, Integer.parseInt(re.group(1)));
                    else if (rs.matches())
                        handleRaceStatus(req, resp, Integer.parseInt(rs.group(1)));
                    else if (rt.matches())
                        handleGetRaceTimes(resp, rt.group(1));
                    else if (RACE_COMPUTED_RESULTS.matcher(path).matches())
                    {
                        java.util.regex.Matcher m = RACE_COMPUTED_RESULTS.matcher(path);
                        if (m.matches())
                            handleComputedResults(req, resp, Integer.parseInt(m.group(1)));
                    }
                    else if (RACE_FINISHERS.matcher(path).matches())
                    {
                        java.util.regex.Matcher m = RACE_FINISHERS.matcher(path);
                        if (m.matches())
                            handleFinishers(req, resp, Integer.parseInt(m.group(1)));
                    }
                    else if (RACE_PENDING_HANDICAPS.matcher(path).matches())
                    {
                        java.util.regex.Matcher m = RACE_PENDING_HANDICAPS.matcher(path);
                        if (m.matches())
                            handleGetPendingHandicaps(resp, m.group(1));
                    }
                    else if (RACE_TCF_SNAPSHOT.matcher(path).matches())
                    {
                        java.util.regex.Matcher m = RACE_TCF_SNAPSHOT.matcher(path);
                        if (m.matches())
                            handleGetRaceTcfSnapshot(resp, m.group(1));
                    }
                    else if (path.matches("/races/[^/]+/startTimes"))
                        todo(resp, "GET " + path + " — compute start sheet");
                    else
                        resp.sendError(404);
                }
            }
        }
        catch (SailSysClient.SailSysException e)
        {
            resp.setStatus(502);
            writeJson(resp, Map.of("error", e.getMessage()));
        }
        catch (Exception e)
        {
            resp.setStatus(500);
            writeJson(resp, Map.of("error", e.getMessage()));
        }
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException
    {
        String path = req.getPathInfo();
        if (path == null) path = "/";
        resp.setContentType("application/json");

        try
        {
            switch (path)
            {
                case "/auth/login" -> handleLogin(req, resp);
                case "/auth/logout" -> handleLogout(req, resp);
                case "/calibration" -> handleSaveCalibration(req, resp);
                default -> {
                    java.util.regex.Matcher tcfs = SERIES_TCFS.matcher(path);
                    java.util.regex.Matcher sc = SERIES_CONFIG.matcher(path);
                    java.util.regex.Matcher rt = RACE_TIMES.matcher(path);
                    java.util.regex.Matcher ev = RACE_ENTRANTS_VIS.matcher(path);
                    java.util.regex.Matcher sv = RACE_STARTTIMES_VIS.matcher(path);
                    java.util.regex.Matcher rp = RACE_PROCESS.matcher(path);
                    if (tcfs.matches())
                        handleSaveTcfs(req, resp, Integer.parseInt(tcfs.group(1)));
                    else if (sc.matches())
                        handleSaveSeriesConfig(req, resp, sc.group(1));
                    else if (rt.matches())
                        handleSaveRaceTimes(req, resp, rt.group(1));
                    else if (ev.matches())
                        handleVisibility(req, resp, Integer.parseInt(ev.group(1)), false);
                    else if (sv.matches())
                        handleVisibility(req, resp, Integer.parseInt(sv.group(1)), true);
                    else if (rp.matches())
                        handleProcessRace(req, resp, Integer.parseInt(rp.group(1)));
                    else if (RACE_PUSH_RESULTS.matcher(path).matches())
                    {
                        java.util.regex.Matcher m = RACE_PUSH_RESULTS.matcher(path);
                        if (m.matches())
                            handlePushResults(req, resp, Integer.parseInt(m.group(1)));
                    }
                    else if (RACE_PROCESS_RESULTS.matcher(path).matches())
                    {
                        java.util.regex.Matcher m = RACE_PROCESS_RESULTS.matcher(path);
                        if (m.matches())
                            handleProcessResults(req, resp, Integer.parseInt(m.group(1)));
                    }
                    else if (RACE_RESULTS_STATUS.matcher(path).matches())
                    {
                        java.util.regex.Matcher m = RACE_RESULTS_STATUS.matcher(path);
                        if (m.matches())
                            handleResultsStatus(req, resp, Integer.parseInt(m.group(1)));
                    }
                    else if (RACE_DIVISION_STARTS.matcher(path).matches())
                    {
                        java.util.regex.Matcher m = RACE_DIVISION_STARTS.matcher(path);
                        if (m.matches())
                            handleDivisionStarts(req, resp, Integer.parseInt(m.group(1)));
                    }
                    else if (RACE_ABANDON.matcher(path).matches())
                    {
                        java.util.regex.Matcher m = RACE_ABANDON.matcher(path);
                        if (m.matches())
                            handleAbandon(req, resp, Integer.parseInt(m.group(1)));
                    }
                    else if (RACE_CALIBRATE.matcher(path).matches())
                    {
                        java.util.regex.Matcher m = RACE_CALIBRATE.matcher(path);
                        if (m.matches())
                            handleCalibrate(req, resp, Integer.parseInt(m.group(1)));
                    }
                    else if (RACE_COURSE_PLAN.matcher(path).matches())
                    {
                        java.util.regex.Matcher m = RACE_COURSE_PLAN.matcher(path);
                        if (m.matches())
                            handleCoursePlan(req, resp, m.group(1));
                    }
                    else if (RACE_PROCESS_HANDICAPS.matcher(path).matches())
                    {
                        java.util.regex.Matcher m = RACE_PROCESS_HANDICAPS.matcher(path);
                        if (m.matches())
                            handleProcessHandicaps(req, resp, m.group(1));
                    }
                    else if (RACE_SAVE_HANDICAPS.matcher(path).matches())
                    {
                        java.util.regex.Matcher m = RACE_SAVE_HANDICAPS.matcher(path);
                        if (m.matches())
                            handleSaveHandicaps(req, resp, m.group(1));
                    }
                    else if (RACE_SAVE_TCFS.matcher(path).matches())
                    {
                        java.util.regex.Matcher m = RACE_SAVE_TCFS.matcher(path);
                        if (m.matches())
                            handleSaveRaceTcfs(req, resp, Integer.parseInt(m.group(1)));
                    }
                    else if (RACE_PUSH_HANDICAPS.matcher(path).matches())
                    {
                        java.util.regex.Matcher m = RACE_PUSH_HANDICAPS.matcher(path);
                        if (m.matches())
                            handlePushRaceHandicaps(req, resp, Integer.parseInt(m.group(1)));
                    }
                    else if (RACE_TCF_SNAPSHOT.matcher(path).matches())
                    {
                        java.util.regex.Matcher m = RACE_TCF_SNAPSHOT.matcher(path);
                        if (m.matches())
                            handleSaveRaceTcfSnapshot(req, resp, m.group(1));
                    }
                    else if (path.matches("/races/[^/]+/results"))
                        todo(resp, "POST " + path + " — save results");
                    else if (path.matches("/races/[^/]+/push"))
                        todo(resp, "POST " + path + " — push to SailSys");
                    else
                        resp.sendError(404);
                }
            }
        }
        catch (SailSysClient.SailSysException e)
        {
            resp.setStatus(502);
            writeJson(resp, Map.of("error", e.getMessage()));
        }
        catch (Exception e)
        {
            resp.setStatus(500);
            writeJson(resp, Map.of("error", e.getMessage()));
        }
    }

    @Override
    protected void doDelete(HttpServletRequest req, HttpServletResponse resp) throws IOException
    {
        String path = req.getPathInfo();
        if (path == null) path = "/";
        resp.setContentType("application/json");

        try
        {
            java.util.regex.Matcher snap = RACE_TCF_SNAPSHOT.matcher(path);
            if (snap.matches())
                handleDeleteRaceTcfSnapshot(req, resp, snap.group(1));
            else
                resp.sendError(404);
        }
        catch (SailSysClient.SailSysException e)
        {
            resp.setStatus(502);
            writeJson(resp, Map.of("error", e.getMessage()));
        }
        catch (Exception e)
        {
            resp.setStatus(500);
            writeJson(resp, Map.of("error", e.getMessage()));
        }
    }

    // --- handlers ---

    private void handleLogin(HttpServletRequest req, HttpServletResponse resp) throws Exception
    {
        Map<String, String> body = readJson(req);
        String email = body.get("email");
        String password = body.get("password");
        if (email == null || email.isBlank() || password == null || password.isEmpty())
        {
            resp.setStatus(400);
            writeJson(resp, Map.of("error", "email and password are required"));
            return;
        }

        // Hand the credentials straight to SailSysClient.login. They go out of
        // scope as soon as this method returns — nothing else captures them.
        SailSysSession session = sailsys.login(email, password);

        HttpSession httpSession = req.getSession(true);
        httpSession.setAttribute(SESSION_ATTR, session);

        writeJson(resp, Map.of(
            "ok", true,
            "user", userSummary(session)));
    }

    private void handleLogout(HttpServletRequest req, HttpServletResponse resp) throws IOException
    {
        HttpSession httpSession = req.getSession(false);
        if (httpSession != null)
            httpSession.invalidate();
        writeJson(resp, Map.of("ok", true));
    }

    private void handleSeries(HttpServletRequest req, HttpServletResponse resp) throws Exception
    {
        SailSysSession session = currentSession(req);
        if (session == null)
        {
            resp.setStatus(401);
            writeJson(resp, Map.of("error", "not signed in"));
            return;
        }
        var series = sailsys.fetchClubSeries(session.token(), config.sailsys().clubId());
        // Ship the numeric-id → shortName/fullName catalogue so the Series
        // page can render handicap names instead of bare ids. Cached
        // server-side via loadHandicapDefinitions so subsequent series-page
        // loads (and the new /handicap-definitions endpoint) don't re-hit
        // SailSys.
        Integer firstSeriesId = series.isEmpty() ? null : series.get(0).id();
        Map<String, Map<String, String>> handicapNames =
            loadHandicapDefinitions(session.token(), firstSeriesId);
        // Pursuit detection is intentionally NOT done here — it requires
        // two SailSys round-trips per series and adds 1–2s to the page
        // load. The client fires per-series checks against
        // /api/series/{id}/is-pursuit after rendering the table, so
        // Configure buttons populate progressively.
        writeJson(resp, Map.of(
            "clubId", config.sailsys().clubId(),
            "handicapDefinitionId", config.sailsys().handicapDefinitionId(),
            "handicapDefinitions", handicapNames,
            "series", series));
    }

    /**
     * GET /api/handicap-definitions — returns the club-wide catalogue as
     * {@code { "<id>": {shortName, fullName} }}. The race page uses this to
     * label the entries in its Handicap selector with friendly names.
     * Reads/populates {@link #handicapDefsCache}; the first call into the
     * cache for a given club fetches via any series the user can see.
     */
    private void handleGetHandicapDefinitions(HttpServletRequest req, HttpServletResponse resp)
        throws Exception
    {
        SailSysSession session = currentSession(req);
        if (session == null)
        {
            resp.setStatus(401);
            writeJson(resp, Map.of("error", "not signed in"));
            return;
        }
        Map<String, Map<String, String>> defs =
            loadHandicapDefinitions(session.token(), null);
        writeJson(resp, Map.of(
            "clubId", config.sailsys().clubId(),
            "handicapDefinitions", defs));
    }

    /**
     * Returns the cached club handicap-definition catalogue, fetching once
     * if absent. {@code seriesIdHint} may be supplied when the caller has
     * already loaded the series list (avoids a redundant /series/all call);
     * pass {@code null} to discover a series id on demand.
     */
    private Map<String, Map<String, String>> loadHandicapDefinitions(
        String token, Integer seriesIdHint)
    {
        int clubId = config.sailsys().clubId();
        Map<String, Map<String, String>> cached = handicapDefsCache.get(clubId);
        if (cached != null) return cached;
        try
        {
            Integer seriesId = seriesIdHint;
            if (seriesId == null)
            {
                var s = sailsys.fetchClubSeries(token, clubId);
                if (!s.isEmpty()) seriesId = s.get(0).id();
            }
            if (seriesId == null)
                return Map.of();
            JsonNode defs = sailsys.fetchHandicapDefinitions(token, seriesId);
            Map<String, Map<String, String>> out = new LinkedHashMap<>();
            if (defs != null && defs.isArray())
            {
                for (JsonNode def : defs)
                {
                    out.put(
                        String.valueOf(def.path("id").asInt()),
                        Map.of(
                            "shortName", def.path("shortName").asText(""),
                            "fullName", def.path("fullName").asText("")));
                }
            }
            handicapDefsCache.put(clubId, out);
            return out;
        }
        catch (Exception x)
        {
            LOG.warn("loadHandicapDefinitions failed; returning empty catalogue", x);
            return Map.of();
        }
    }

    /**
     * GET /api/series/{id}/spinnaker — the per-boat spinnaker designation for
     * the series, as {@code { seriesId, spinnakerByBoat: { "<boatId>": 1|2 } }}
     * (1 = spinnaker, 2 = non-spinnaker). SailSys keeps this on the series ENTRY
     * (not the per-race entrants payload), so the race page joins it in to show
     * the S / NS column.
     */
    private void handleSeriesSpinnaker(HttpServletRequest req, HttpServletResponse resp,
                                       int seriesId) throws Exception
    {
        SailSysSession session = currentSession(req);
        if (session == null)
        {
            resp.setStatus(401);
            writeJson(resp, Map.of("error", "not signed in"));
            return;
        }
        Map<String, Integer> byBoat = new LinkedHashMap<>();
        try
        {
            JsonNode entries = sailsys.fetchSeriesEntries(session.token(), seriesId);
            if (entries != null && entries.isArray())
            {
                for (JsonNode it : entries)
                {
                    int boatId = it.path("boatId").asInt(0);
                    if (boatId == 0 || it.path("spinnakerType").isMissingNode()
                        || it.path("spinnakerType").isNull()) continue;
                    byBoat.put(String.valueOf(boatId), it.path("spinnakerType").asInt());
                }
            }
        }
        catch (Exception e)
        {
            LOG.warn("fetchSeriesEntries failed for series {}: {}", seriesId, e.toString());
        }
        writeJson(resp, Map.of("seriesId", seriesId, "spinnakerByBoat", byBoat));
    }

    /**
     * GET /api/series/{id}/is-pursuit — does the series's first race report
     * pursuit-style? Returns {@code {seriesId, isPursuit}}. Called by the
     * Series page client-side, one per row, in parallel, so Configure
     * buttons populate progressively instead of blocking the page on a
     * batch determination.
     */
    private void handleIsSeriesPursuit(HttpServletRequest req, HttpServletResponse resp,
                                       int seriesId) throws Exception
    {
        SailSysSession session = currentSession(req);
        if (session == null)
        {
            resp.setStatus(401);
            writeJson(resp, Map.of("error", "not signed in"));
            return;
        }
        boolean isPursuit = seriesHasPursuit(session.token(), seriesId);
        writeJson(resp, Map.of("seriesId", seriesId, "isPursuit", isPursuit));
    }

    /**
     * True when the given series is pursuit (top-level {@code raceType == 1}).
     * Returns false on any error so a missing or unreadable series doesn't
     * break the Series page.
     *
     * <p>Fast path: {@code GET /series/{id}} carries {@code raceType}
     * directly — one round-trip and we're done. Race officers (adminLevel=1)
     * are forbidden from that endpoint, so we fall back to the older path
     * (race list + first race's status — two round-trips) on any failure.
     * Either way we only ever look at the FIRST race in the series; a
     * club's series is overwhelmingly homogeneous (Twilight = all pursuit,
     * Championship = all scratch), and {@code divisionTiming[].raceType}
     * on the race-list summaries is always 0 so it can't substitute.
     */
    private boolean seriesHasPursuit(String token, int seriesId)
    {
        Boolean cached = pursuitCache.get(seriesId);
        if (cached != null)
            return cached;
        boolean result = computeSeriesHasPursuit(token, seriesId);
        pursuitCache.put(seriesId, result);
        return result;
    }

    private boolean computeSeriesHasPursuit(String token, int seriesId)
    {
        try
        {
            JsonNode detail = sailsys.fetchSeriesDetail(token, seriesId);
            if (detail != null && detail.has("raceType") && !detail.path("raceType").isNull())
                return detail.path("raceType").asInt(0) == 1;
        }
        catch (Exception e)
        {
            LOG.debug("fetchSeriesDetail failed for series {} ({}); falling back to race-list path",
                seriesId, e.toString());
        }
        try
        {
            JsonNode races = sailsys.fetchSeriesRaces(token, seriesId);
            if (races == null || !races.isArray() || races.isEmpty())
                return false;
            int firstRaceId = races.get(0).path("id").asInt(0);
            if (firstRaceId == 0)
                return false;
            JsonNode status = sailsys.fetchRaceStatus(token, firstRaceId);
            return status.path("raceType").asInt(0) == 1;
        }
        catch (Exception e)
        {
            LOG.warn("Couldn't determine pursuit status for series {}: {}",
                seriesId, e.toString());
            return false;
        }
    }

    /**
     * GET /api/series/{id}/config — return the Jinx algorithm settings for
     * this series. Falls back to the yaml defaults when nothing is saved.
     * Response also carries the defaults block so the UI can offer a
     * "restore defaults" action without a second round-trip.
     */
    private void handleGetSeriesConfig(HttpServletResponse resp, String seriesId) throws Exception
    {
        JinxConfig.Algorithm saved = store.seriesConfig(seriesId);
        JinxConfig.Algorithm defaults = config.algorithm();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("seriesId", seriesId);
        out.put("isCustom", saved != null);
        out.put("config", algorithmMap(saved != null ? saved : defaults));
        out.put("defaults", algorithmMap(defaults));
        writeJson(resp, out);
    }

    /**
     * POST /api/series/{id}/config — persist a per-series Jinx algorithm
     * override. The body shape matches the {@code config} block returned by
     * the GET endpoint. Jackson's canonical-constructor pass through
     * {@link JinxConfig.Algorithm} fills in safe defaults for any missing
     * or invalid field, so callers can post a partial payload.
     */
    private void handleSaveSeriesConfig(HttpServletRequest req, HttpServletResponse resp, String seriesId)
        throws Exception
    {
        JinxConfig.Algorithm posted = MAPPER.readValue(req.getInputStream(), JinxConfig.Algorithm.class);
        store.putSeriesConfig(seriesId, posted);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("seriesId", seriesId);
        out.put("isCustom", true);
        out.put("config", algorithmMap(posted));
        writeJson(resp, out);
    }

    private static Map<String, Object> algorithmMap(JinxConfig.Algorithm a)
    {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("penaltyList", a.penaltyList());
        m.put("idealRaceDuration", a.idealRaceDuration());
        m.put("dnfAllowance", a.dnfAllowance());
        m.put("earliestStart", a.earliestStart());
        m.put("latitude", a.latitude());
        m.put("longitude", a.longitude());
        m.put("limitBySunset", a.limitBySunset());
        return m;
    }

    private void handleSeriesRaces(HttpServletRequest req, HttpServletResponse resp, int seriesId)
        throws Exception
    {
        SailSysSession session = currentSession(req);
        if (session == null)
        {
            resp.setStatus(401);
            writeJson(resp, Map.of("error", "not signed in"));
            return;
        }

        JsonNode races = sailsys.fetchSeriesRaces(session.token(), seriesId);
        // Status bundling removed — the client fetches /api/races/{id}/status
        // in parallel and renders progressively. Bundling here would block
        // the response on the slowest race's status (typically 300–800 ms ×
        // N sequential, or 300–800 ms parallel) before the table can render.
        // SailSys's per-series race summary doesn't carry seriesName, so we
        // resolve it once via the club series list. This is what feeds the
        // Series column + filter chip on the races page. Failure (e.g. a race
        // officer who can't list series — shouldn't happen since the filtered
        // route is admin-only, but be defensive) just leaves seriesName null
        // and the client falls back to "id N".
        String seriesName = null;
        try
        {
            for (org.mortbay.sailing.jinx.model.Series s :
                sailsys.fetchClubSeries(session.token(), config.sailsys().clubId()))
            {
                if (s != null && s.id() == seriesId) { seriesName = s.name(); break; }
            }
        }
        catch (Exception e)
        {
            LOG.debug("Could not resolve seriesName for {}: {}", seriesId, e.toString());
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("seriesId", seriesId);
        out.put("seriesName", seriesName);
        out.put("races", races);
        writeJson(resp, out);
    }

    /**
     * GET /api/races/current — paged current/upcoming races for the
     * configured club. Used when the Races page is opened without a
     * {@code ?seriesId=} filter (the only mode available to race officers,
     * who lack permission to enumerate series). Returns only the races
     * list; the client fetches per-race status progressively for fastest
     * time-to-first-render.
     */
    private void handleCurrentRaces(HttpServletRequest req, HttpServletResponse resp)
        throws Exception
    {
        SailSysSession session = currentSession(req);
        if (session == null)
        {
            resp.setStatus(401);
            writeJson(resp, Map.of("error", "not signed in"));
            return;
        }

        JsonNode races = sailsys.fetchCurrentRaces(session.token(), config.sailsys().clubId());
        writeJson(resp, Map.of(
            "clubId", config.sailsys().clubId(),
            "races", races));
    }


    private void handleRaceEntrants(HttpServletRequest req, HttpServletResponse resp, int raceId)
        throws Exception
    {
        SailSysSession session = currentSession(req);
        if (session == null)
        {
            resp.setStatus(401);
            writeJson(resp, Map.of("error", "not signed in"));
            return;
        }
        writeJson(resp, Map.of(
            "raceId", raceId,
            "entrants", sailsys.fetchRaceEntrants(session.token(), raceId)));
    }

    private void handleRaceStatus(HttpServletRequest req, HttpServletResponse resp, int raceId)
        throws Exception
    {
        SailSysSession session = currentSession(req);
        if (session == null)
        {
            resp.setStatus(401);
            writeJson(resp, Map.of("error", "not signed in"));
            return;
        }
        writeJson(resp, Map.of(
            "raceId", raceId,
            "status", sailsys.fetchRaceStatus(session.token(), raceId)));
    }

    /**
     * Bulk MYC TCF update for the active race. Body: {@code {updates:[{boatId,
     * value},...]}}. Iterates per-boat with a 300ms gap (per
     * {@code sailsys-api-reference.md} §8 — writes must not be parallelised
     * and a 200-500ms cushion is recommended). Per-update failures are
     * captured rather than fail-fast so the UI can show exactly which boats
     * went through and which need a retry.
     */
    private void handleSaveTcfs(HttpServletRequest req, HttpServletResponse resp, int seriesId)
        throws Exception
    {
        SailSysSession session = currentSession(req);
        if (session == null)
        {
            resp.setStatus(401);
            writeJson(resp, Map.of("error", "not signed in"));
            return;
        }

        JsonNode body = MAPPER.readTree(req.getInputStream());
        JsonNode updates = body.path("updates");
        if (!updates.isArray() || updates.isEmpty())
        {
            resp.setStatus(400);
            writeJson(resp, Map.of("error", "updates array required"));
            return;
        }

        List<Map<String, Object>> results = new ArrayList<>();
        boolean allOk = true;
        boolean first = true;
        for (JsonNode u : updates)
        {
            if (!first) Thread.sleep(300L);
            first = false;
            int boatId = u.path("boatId").asInt();
            double value = u.path("value").asDouble();
            // spinnakerType must match the existing handicap row — see
            // SailSysClient.updateHandicap javadoc. The client reads it from
            // the entrants payload; default to 1 (the observed MYC TCF value)
            // if not provided so the call has a sensible fallback.
            int spinnakerType = u.path("spinnakerType").isMissingNode() || u.path("spinnakerType").isNull()
                ? 1 : u.path("spinnakerType").asInt();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("boatId", boatId);
            row.put("value", value);
            row.put("spinnakerType", spinnakerType);
            try
            {
                sailsys.updateHandicap(session.token(), seriesId, boatId, value, spinnakerType);
                row.put("ok", true);
            }
            catch (Exception e)
            {
                LOG.warn("TCF update failed for boat {} in series {}: {}",
                    boatId, seriesId, e.toString());
                row.put("ok", false);
                row.put("error", e.getMessage());
                allOk = false;
            }
            results.add(row);
        }

        writeJson(resp, Map.of(
            "ok", allOk,
            "seriesId", seriesId,
            "results", results));
    }

    /**
     * Push the RO-captured race results (came / finish / flags) to SailSys.
     *
     * <p>Request body:
     * <pre>
     * {
     *   "boats": {
     *     "767": { "came": true, "finish": "18:31:00", "flags": ["OCS"] },
     *     "770": { "came": false, "finish": null, "flags": ["DNC"] },
     *     ...
     *   }
     * }
     * </pre>
     *
     * <p>Per HAR-5 the SailSys flow is:
     * <ol>
     *   <li>GET race status — extract {@code resultSaveToken} (a monotonic
     *       version counter) and {@code dateTime} (race date YYYY-MM-DD).</li>
     *   <li>GET /races/{raceId}/results/finishers — full per-boat template
     *       carrying all the fields SailSys insists on echoing back.</li>
     *   <li>For each unique divisionId in the template, GET the penalty
     *       catalogue; build a lookup keyed by short name (DNC/DNF/OCS/...).</li>
     *   <li>Mutate the template in-place from the request body:
     *       {@code startedRace = came}; {@code finishTime} from
     *       {@code raceDate + "T" + finish + ".000"} when both came and finish
     *       are present; {@code penalties} = list of penalty defs matching the
     *       flags. AVG is intentionally not pushed — SailSys has no AVG
     *       penalty; duty boat just lands as a non-starter.</li>
     *   <li>PUT starters with the current saveToken, capture the new
     *       resultSaveToken from the response.</li>
     *   <li>PUT finishers with the bumped saveToken.</li>
     * </ol>
     */
    private void handlePushResults(HttpServletRequest req, HttpServletResponse resp, int raceId)
        throws Exception
    {
        SailSysSession session = currentSession(req);
        if (session == null)
        {
            resp.setStatus(401);
            writeJson(resp, Map.of("error", "not signed in"));
            return;
        }

        JsonNode body = MAPPER.readTree(req.getInputStream());
        JsonNode boats = body.path("boats");
        if (!boats.isObject())
        {
            resp.setStatus(400);
            writeJson(resp, Map.of("error", "boats object required"));
            return;
        }

        String token = session.token();

        // 1. Status — for resultSaveToken + race date.
        JsonNode status = sailsys.fetchRaceStatus(token, raceId);
        int saveToken = status.path("resultSaveToken").asInt(0);
        String raceDate = status.path("dateTime").asText("");
        if (raceDate.length() >= 10) raceDate = raceDate.substring(0, 10);
        else raceDate = java.time.LocalDate.now().toString();


        // 2. Template — every entered boat with the full echo-back fields.
        //    Source from /results/starters: it returns every boat regardless
        //    of startedRace state, while /results/finishers can come back
        //    empty after a complete wipe (which would silently make this
        //    whole flow a no-op). If /starters is also empty (race never
        //    had results, or SailSys garbage-collected the records), fall
        //    back to constructing the template from /entrants.
        JsonNode startersNode = sailsys.fetchRaceStarters(token, raceId);
        ArrayNode template;
        if (startersNode.isArray() && startersNode.size() > 0)
        {
            template = (ArrayNode) startersNode;
        }
        else
        {
            template = buildTemplateFromEntrants(
                sailsys.fetchRaceEntrants(token, raceId));
            if (template.isEmpty())
            {
                resp.setStatus(502);
                writeJson(resp, Map.of("error",
                    "race has no entrants and no result template — cannot save"));
                return;
            }
        }

        // 3. Penalty catalogue per division. Cached so we don't refetch for
        //    every boat in the same division.
        Map<Integer, Map<String, JsonNode>> penaltiesByDivision = new HashMap<>();
        for (JsonNode entry : template)
        {
            int divisionId = entry.path("divisionId").asInt();
            if (divisionId == 0 || penaltiesByDivision.containsKey(divisionId)) continue;
            JsonNode penList = sailsys.fetchDivisionPenalties(token, divisionId);
            Map<String, JsonNode> byShort = new LinkedHashMap<>();
            if (penList.isArray())
            {
                for (JsonNode p : penList)
                    byShort.put(p.path("definitionShortName").asText(), p);
            }
            penaltiesByDivision.put(divisionId, byShort);
        }

        // 4. Mutate the template.
        for (JsonNode entry : template)
        {
            if (!(entry instanceof ObjectNode obj)) continue;
            String boatIdStr = String.valueOf(obj.path("boatId").asInt());
            JsonNode bs = boats.path(boatIdStr);
            if (bs.isMissingNode())
                continue; // boat absent from the client payload — leave untouched

            boolean came = bs.path("came").asBoolean(false);
            obj.put("startedRace", came);

            // SailSys requires a boat to be a starter before it can be a
            // finisher. We satisfy that two ways:
            //   1. Order — starters PUT runs before the finishers PUT below
            //      (and resultSaveToken chains, so the starter flag is
            //      committed before we attempt to record a finish).
            //   2. Filter — finishTime is ONLY set here when came=true. A
            //      non-starter therefore goes to SailSys with finishTime=null,
            //      so even if the order were reversed by accident, SailSys
            //      would never see a finish on a non-starter.
            String finish = bs.path("finish").isNull() ? null : bs.path("finish").asText(null);
            if (came && finish != null && !finish.isEmpty())
            {
                obj.put("finishTime", raceDate + "T" + finish + ".000");
                obj.put("finishDate", raceDate + "T00:00:00.000");
            }
            else
            {
                obj.putNull("finishTime");
                // SailSys leaves finishDate populated even when finishTime is
                // null — match that to avoid round-trip drift.
                obj.put("finishDate", raceDate + "T00:00:00.000");
            }

            ArrayNode penalties = MAPPER.createArrayNode();
            Map<String, JsonNode> defs = penaltiesByDivision.get(obj.path("divisionId").asInt());
            if (bs.path("flags").isArray())
            {
                for (JsonNode flag : bs.path("flags"))
                {
                    String flagName = flag.asText();
                    if ("AVG".equals(flagName))
                    {
                        // SailSys represents "give this boat average points"
                        // (i.e. the duty boat) as a single-field penalty
                        // stub: {"averagePoints": true}. No definitionId,
                        // no shortname. Verified from HAR-11.
                        ObjectNode avgPen = MAPPER.createObjectNode();
                        avgPen.put("averagePoints", true);
                        penalties.add(avgPen);
                        continue;
                    }
                    if (defs == null) continue;
                    JsonNode penDef = defs.get(flagName);
                    if (penDef != null) penalties.add(penDef);
                    // Other unknown flags are silently dropped.
                }
            }
            obj.set("penalties", penalties);
        }

        // 5a. Pre-clear pass — SailSys rejects PUT starters when a boat
        //     transitions startedRace=true→false while its finishTime is
        //     still set ("you can't unstart it"). To get out of that bind,
        //     send a finishers PUT first that clears finishTime + penalties
        //     ONLY for boats being un-started. We build it from a fresh
        //     fetch (NOT a copy of the mutated `template`) so unrelated
        //     boats aren't pre-mutated. Skipped entirely when no boat is
        //     being un-started, so the common path stays at two PUTs.
        ArrayNode preTemplate;
        JsonNode preFetch = sailsys.fetchRaceStarters(token, raceId);
        if (preFetch.isArray() && preFetch.size() > 0)
            preTemplate = (ArrayNode) preFetch;
        else
            preTemplate = MAPPER.createArrayNode(); // nothing to pre-clear
        boolean anyBeingUnstarted = false;
        for (JsonNode entry : preTemplate)
        {
            if (!(entry instanceof ObjectNode obj)) continue;
            String boatIdStr = String.valueOf(obj.path("boatId").asInt());
            JsonNode bs = boats.path(boatIdStr);
            if (bs.isMissingNode()) continue;
            boolean wantCame = bs.path("came").asBoolean(false);
            JsonNode currentFinish = obj.path("finishTime");
            boolean currentlyFinished =
                currentFinish != null && !currentFinish.isNull()
                    && !currentFinish.asText("").isEmpty();
            if (!wantCame && currentlyFinished)
            {
                obj.putNull("finishTime");
                obj.set("penalties", MAPPER.createArrayNode());
                anyBeingUnstarted = true;
            }
        }
        if (anyBeingUnstarted)
        {
            JsonNode afterPre = sailsys.putRaceFinishers(token, raceId, saveToken, preTemplate);
            saveToken = afterPre.path("resultSaveToken").asInt(saveToken + 1);
            Thread.sleep(300L);
        }

        // 5b. PUT starters — startedRace flag goes first.
        JsonNode afterStarters = sailsys.putRaceStarters(token, raceId, saveToken, template);
        int newToken = afterStarters.path("resultSaveToken").asInt(saveToken + 1);

        // Brief gap before the second PUT per the rate-limit guidance in
        // sailsys-api-reference §8.
        Thread.sleep(300L);

        // 6. PUT finishers — finishTime + penalties. Echo back the same array
        //    we just PUT for starters (mutations are already in place).
        sailsys.putRaceFinishers(token, raceId, newToken, template);

        // 7. Refetch status. The race object echoed by PUT finishers reflects
        //    the moment that PUT was applied; SailSys may update fields like
        //    requiresResultCalculation immediately afterwards as the
        //    pointscore/recalc machinery kicks in. A fresh GET /status is the
        //    authoritative source for "is recalculation needed?", which the
        //    Process Results button on the client depends on.
        JsonNode freshStatus = sailsys.fetchRaceStatus(token, raceId);

        writeJson(resp, Map.of(
            "ok", true,
            "raceId", raceId,
            "status", freshStatus));
    }

    /**
     * GET the SailSys-computed results for a race (per-boat place, points,
     * authoritative penalties, elapsed). Caller should only use this when
     * {@code requiresResultCalculation} is false on the race status.
     */
    private void handleComputedResults(HttpServletRequest req, HttpServletResponse resp, int raceId)
        throws Exception
    {
        SailSysSession session = currentSession(req);
        if (session == null)
        {
            resp.setStatus(401);
            writeJson(resp, Map.of("error", "not signed in"));
            return;
        }
        writeJson(resp, Map.of(
            "raceId", raceId,
            "results", sailsys.fetchRaceResults(session.token(), raceId)));
    }

    /**
     * GET the per-boat results template for a race ({@code startedRace},
     * {@code finishTime}, penalties — the raw fields kept on the SailSys
     * results endpoint, not the computed placings). Used by the race page on
     * load so that "did this boat finish, and at what time" is sourced from
     * SailSys rather than local JSON — local storage only holds the per-boat
     * {@code actualStart} (which SailSys doesn't model) plus boat order /
     * duty boat.
     *
     * <p>Backed by SailSys's /results/starters (not /finishers) since
     * /finishers comes back empty when no boat has a finishTime, while
     * /starters returns every entered boat regardless of state. Field name
     * stays {@code finishers} on the response for client compatibility.
     */
    private void handleFinishers(HttpServletRequest req, HttpServletResponse resp, int raceId)
        throws Exception
    {
        SailSysSession session = currentSession(req);
        if (session == null)
        {
            resp.setStatus(401);
            writeJson(resp, Map.of("error", "not signed in"));
            return;
        }
        writeJson(resp, Map.of(
            "raceId", raceId,
            "finishers", sailsys.fetchRaceStarters(session.token(), raceId)));
    }

    /**
     * Trigger a results recalculation on SailSys and wait for it to complete.
     * Calls GET /results/check (kicks off the calc), then polls
     * /races/{id}/status every 500 ms until {@code requiresResultCalculation}
     * is false (or a 90 s deadline elapses — SailSys can be slow under load).
     * Returns the final status plus the freshly-computed results in one round
     * trip.
     */
    private void handleProcessResults(HttpServletRequest req, HttpServletResponse resp, int raceId)
        throws Exception
    {
        SailSysSession session = currentSession(req);
        if (session == null)
        {
            resp.setStatus(401);
            writeJson(resp, Map.of("error", "not signed in"));
            return;
        }
        String token = session.token();

        // SailSys's actual "process results" trigger is PUT
        // /races/{id}/results/penalties/complete, NOT GET /results/check
        // (which is a no-op on published races — verified against HAR-16).
        // The PUT body carries the default DNF + DNC penalty definitions;
        // SailSys uses them as the fallback for non-finishers / non-starters
        // during the recalc. The race transitions to resultStatus=1 (Hidden,
        // pending recalc) immediately on this PUT and SailSys restores the
        // original publish state automatically once the recalc completes —
        // so no un-publish/re-promote dance is needed.
        JsonNode catalogue = sailsys.fetchRacePenaltiesComplete(token, raceId);
        JsonNode dnf = findPenaltyByShortName(catalogue, "DNF");
        JsonNode dnc = findPenaltyByShortName(catalogue, "DNC");
        if (dnf == null || dnc == null)
        {
            resp.setStatus(502);
            writeJson(resp, Map.of("error",
                "SailSys penalty catalogue missing DNF/DNC entries — cannot trigger recalc."));
            return;
        }
        ObjectNode body = MAPPER.createObjectNode();
        body.set("nonFinisherPenalty", dnf);
        body.set("nonStartersPenalty", dnc);
        sailsys.putRacePenaltiesComplete(token, raceId, body);

        long deadline = System.currentTimeMillis() + 90_000L;
        JsonNode status = null;
        boolean done = false;
        while (System.currentTimeMillis() < deadline)
        {
            Thread.sleep(500L);
            status = sailsys.fetchRaceStatus(token, raceId);
            if (!status.path("requiresResultCalculation").asBoolean(true))
            {
                done = true;
                break;
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", done);
        out.put("raceId", raceId);
        out.put("status", status);
        if (done)
            out.put("results", sailsys.fetchRaceResults(token, raceId));
        else
            out.put("error", "Results still calculating after 90s — try again in a moment.");
        writeJson(resp, out);
    }

    /**
     * Looks up a penalty definition by its {@code definitionShortName}
     * (e.g. "DNF", "DNC") in the array returned by
     * {@link SailSysClient#fetchRacePenaltiesComplete}. Returns {@code null}
     * when no entry matches.
     */
    private static JsonNode findPenaltyByShortName(JsonNode catalogue, String shortName)
    {
        if (!catalogue.isArray()) return null;
        for (JsonNode p : catalogue)
        {
            if (shortName.equals(p.path("definitionShortName").asText(null)))
                return p;
        }
        return null;
    }

    /**
     * Set the results visibility status. Body: {@code {status: "hidden" |
     * "provisional" | "final"}} (or the raw numeric 0/1/2). The mapping to
     * SailSys's URL path matches the
     * {@link SailSysClient#setResultsStatus} javadoc:
     * 0=Hidden, 1=Provisional, 2=Final.
     */
    private void handleResultsStatus(HttpServletRequest req, HttpServletResponse resp, int raceId)
        throws Exception
    {
        SailSysSession session = currentSession(req);
        if (session == null)
        {
            resp.setStatus(401);
            writeJson(resp, Map.of("error", "not signed in"));
            return;
        }
        JsonNode body = MAPPER.readTree(req.getInputStream());
        int statusCode;
        if (body.path("status").isInt())
            statusCode = body.path("status").asInt();
        else
        {
            String s = body.path("status").asText("").toLowerCase();
            statusCode = switch (s)
            {
                case "hidden"      -> 0;
                case "provisional" -> 1;
                case "final"       -> 2;
                default            -> -1;
            };
        }
        if (statusCode < 0 || statusCode > 2)
        {
            resp.setStatus(400);
            writeJson(resp, Map.of("error",
                "status must be hidden/provisional/final (or 0/1/2)"));
            return;
        }
        sailsys.setResultsStatus(session.token(), raceId, statusCode);
        // Echo back fresh status so the UI can re-render without a second round trip.
        writeJson(resp, Map.of(
            "ok", true,
            "raceId", raceId,
            "status", sailsys.fetchRaceStatus(session.token(), raceId)));
    }

    /**
     * Publish/unpublish either the entrants list or the start sheet for a race.
     * Body: {@code {published: true|false}}. Returns the updated status.
     */
    private void handleVisibility(HttpServletRequest req, HttpServletResponse resp,
                                  int raceId, boolean startTimes) throws Exception
    {
        SailSysSession session = currentSession(req);
        if (session == null)
        {
            resp.setStatus(401);
            writeJson(resp, Map.of("error", "not signed in"));
            return;
        }
        JsonNode body = MAPPER.readTree(req.getInputStream());
        boolean published = body.path("published").asBoolean(false);
        if (startTimes)
            sailsys.setStartTimesVisibility(session.token(), raceId, published);
        else
            sailsys.setEntrantsVisibility(session.token(), raceId, published);
        // Refetch status so the client sees the up-to-date state in one round trip.
        writeJson(resp, Map.of(
            "ok", true,
            "raceId", raceId,
            "status", sailsys.fetchRaceStatus(session.token(), raceId)));
    }

    /**
     * Save per-division start times (and, for pursuit races, course lengths)
     * to SailSys. This is the persist-only path used by the main Save button
     * for both race types. Body:
     * {@code {divisions: {"<divisionId>": {startTimeLocal: "HH:MM", courseLength?: n}, ...}}}.
     * The legacy {@code {starts: {"<divisionId>": "HH:MM:SS"}}} shape is also
     * accepted (see {@link #divisionsFromBody}).
     *
     * <p>For a scratch start the {@code startTimeLocal} is the division's
     * allocated gun time; for a pursuit race it is the division's earliest
     * start, and {@code courseLength} configures the staggering. SailSys has no
     * separate scheduled-vs-actual concept — both live on
     * {@code divisionTiming[i]}, written via PUT {@code /races/{id}/timing}.
     * Steps: GET status for the current divisionTiming template, apply the
     * per-division patch ({@link #patchDivisionTiming}), PUT the whole array,
     * then return the fresh status so the client can refresh the panel. No
     * polling — a successful PUT triggers SailSys-side processing, but the
     * caller doesn't wait for the staggered sheet here (that's
     * {@link #handleProcessRace}).
     */
    private void handleDivisionStarts(HttpServletRequest req, HttpServletResponse resp, int raceId)
        throws Exception
    {
        SailSysSession session = currentSession(req);
        if (session == null)
        {
            resp.setStatus(401);
            writeJson(resp, Map.of("error", "not signed in"));
            return;
        }
        JsonNode body = MAPPER.readTree(req.getInputStream());
        ObjectNode divisions = divisionsFromBody(body);
        if (divisions.isEmpty())
        {
            resp.setStatus(400);
            writeJson(resp, Map.of("error", "divisions (or starts) object required"));
            return;
        }

        JsonNode status = sailsys.fetchRaceStatus(session.token(), raceId);
        JsonNode divisionTiming = status.path("divisionTiming");
        if (!divisionTiming.isArray() || divisionTiming.isEmpty())
        {
            resp.setStatus(400);
            writeJson(resp, Map.of("error", "race has no divisionTiming"));
            return;
        }

        ArrayNode patched = patchDivisionTiming(divisionTiming, divisions);
        sailsys.setRaceTiming(session.token(), raceId, patched);
        // Refetch so the client sees the post-PUT state — including the new
        // startTimeLocal values that just landed.
        JsonNode freshStatus = sailsys.fetchRaceStatus(session.token(), raceId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("raceId", raceId);
        out.put("status", freshStatus);
        writeJson(resp, out);
    }

    /**
     * Construct a synthetic results template from the /entrants response —
     * used as a fallback when SailSys's /results/starters and /finishers
     * both return empty arrays (race never had results, or all records
     * were garbage-collected after a complete wipe).
     *
     * <p>The shape is flattened to match what /results/starters normally
     * returns (HAR-derived): a top-level {@code boatId}, {@code boatName},
     * {@code divisionId}, a flat {@code skipper} object, and the
     * mutable-on-PUT fields {@code startedRace}, {@code finishTime},
     * {@code finishDate}, {@code penalties}, etc. set to safe defaults.
     * The subsequent mutation loop fills in {@code startedRace} /
     * {@code finishTime} / {@code penalties} from the client payload.
     */
    private static ArrayNode buildTemplateFromEntrants(JsonNode entrants)
    {
        ArrayNode out = MAPPER.createArrayNode();
        if (entrants == null || !entrants.isArray()) return out;
        for (JsonNode e : entrants)
        {
            JsonNode boat = e.path("boat");
            JsonNode division = e.path("division");
            JsonNode skipperProfile = e.path("skipper").path("profile");
            int boatId = boat.path("id").asInt(0);
            if (boatId == 0) continue;
            ObjectNode entry = MAPPER.createObjectNode();
            // Flat skipper — match HAR shape (no nested .profile).
            ObjectNode skipper = MAPPER.createObjectNode();
            skipper.put("userId", skipperProfile.path("userId").asInt(0));
            skipper.putNull("pendingUserId");
            skipper.put("firstname", skipperProfile.path("firstname").asText(""));
            skipper.put("surname", skipperProfile.path("surname").asText(""));
            skipper.put("email", skipperProfile.path("email").asText(""));
            skipper.put("phonePrefix", skipperProfile.path("phonePrefix").asText(""));
            skipper.put("phoneNumber", skipperProfile.path("phoneNumber").asText(""));
            String first = skipperProfile.path("firstname").asText("");
            String last = skipperProfile.path("surname").asText("");
            String full = (first + " " + last).trim();
            skipper.put("fullName", full);
            skipper.put("skipperDisplayName", full);
            skipper.put("displayName", full);
            entry.set("skipper", skipper);
            entry.put("boatId", boatId);
            entry.put("boatName", boat.path("name").asText(""));
            entry.put("sailNumber", boat.path("sailNumber").asText(""));
            entry.put("boatCountry", boat.path("country").asText(""));
            entry.putNull("boatBowNumber");
            entry.put("boatMake", boat.path("make").asText(""));
            entry.put("boatModel", boat.path("model").asText(""));
            entry.put("threeLetterIsoCode", boat.path("threeLetterIsoCode").asText(""));
            entry.put("division", division.path("name").asText(""));
            entry.put("divisionId", division.path("id").asInt(0));
            entry.putNull("startTimeOffset");
            entry.put("startTimeLocal", e.path("startTimeLocal").asText(""));
            entry.put("startedRace", false);
            entry.put("protest", e.path("protest").asBoolean(false));
            entry.putNull("finishTime");
            entry.putNull("finishDate");
            entry.set("penalties", MAPPER.createArrayNode());
            entry.putNull("raceSignOnOff");
            entry.putNull("teamDto");
            entry.put("boatCrewInfoComplete", e.path("boatCrewInfoComplete").asBoolean(false));
            entry.set("crew", e.path("crew").isArray() ? e.path("crew").deepCopy() : MAPPER.createArrayNode());
            entry.set("potentialCrew", MAPPER.createArrayNode());
            out.add(entry);
        }
        return out;
    }

    /**
     * Replace the time portion of an ISO-ish "YYYY-MM-DDTHH:MM:SS.sss" string
     * with the given HH:MM:SS. Keeps the date prefix and the millisecond
     * suffix shape. Returns the input unchanged if it doesn't have the
     * expected structure.
     */
    /**
     * Patch a {@code divisionTiming} array (the SailSys /status / /timing shape)
     * from a per-division {@code divisions} object:
     * {@code {"<divisionId>": {startTimeLocal?: "HH:MM", courseLength?: number}}}.
     *
     * <p>For each referenced division: when {@code startTimeLocal} is a
     * non-empty string the division's {@code startTimeLocal} is rewritten with
     * the new time portion ({@link #replaceTimePortion}, which accepts both
     * {@code HH:MM} and {@code HH:MM:SS}) and {@code startTimeUtc} is recomputed
     * preserving the original local→UTC offset; when {@code courseLength} is a
     * number it overwrites the division's course length. All other fields, and
     * any division not named in {@code divisions}, are preserved verbatim — the
     * whole array is what gets PUT back to SailSys, which rejects partial or
     * altered entries.
     *
     * <p>Operates on a deep copy; the input array is never mutated.
     */
    /**
     * Normalise a request body into the unified {@code divisions} object
     * consumed by {@link #patchDivisionTiming}. Accepts the new
     * {@code {divisions: {"<id>": {startTimeLocal, courseLength}}}} shape and
     * the legacy {@code {starts: {"<id>": "HH:MM:SS"}}} shape (converted to
     * {@code {"<id>": {startTimeLocal: "HH:MM:SS"}}}). Returns an empty object
     * when neither is present.
     */
    static ObjectNode divisionsFromBody(JsonNode body)
    {
        JsonNode divisions = body.path("divisions");
        if (divisions.isObject() && !divisions.isEmpty()) return (ObjectNode) divisions;
        JsonNode starts = body.path("starts");
        ObjectNode out = MAPPER.createObjectNode();
        if (starts.isObject())
        {
            java.util.Iterator<String> it = starts.fieldNames();
            while (it.hasNext())
            {
                String id = it.next();
                String hhmmss = starts.path(id).asText("");
                if (!hhmmss.isEmpty()) out.putObject(id).put("startTimeLocal", hhmmss);
            }
        }
        return out;
    }

    static ArrayNode patchDivisionTiming(JsonNode divisionTiming, JsonNode divisions)
    {
        ArrayNode patched = (ArrayNode) divisionTiming.deepCopy();
        java.util.Iterator<String> it = divisions.fieldNames();
        while (it.hasNext())
        {
            String divIdStr = it.next();
            int divId;
            try { divId = Integer.parseInt(divIdStr); }
            catch (NumberFormatException e) { continue; }
            JsonNode spec = divisions.path(divIdStr);
            for (JsonNode dt : patched)
            {
                if (!(dt instanceof ObjectNode on)) continue;
                if (on.path("divisionId").asInt() != divId) continue;
                JsonNode start = spec.path("startTimeLocal");
                if (start.isTextual() && !start.asText().isEmpty())
                {
                    String origLocal = on.path("startTimeLocal").asText("");
                    String origUtc = on.path("startTimeUtc").asText("");
                    String newLocal = replaceTimePortion(origLocal, start.asText());
                    on.put("startTimeLocal", newLocal);
                    on.put("startTimeUtc", recomputeUtc(origLocal, origUtc, newLocal));
                }
                JsonNode course = spec.path("courseLength");
                if (course.isNumber()) on.put("courseLength", course.asDouble());
                break;
            }
        }
        return patched;
    }

    private static String replaceTimePortion(String iso, String hhmmss)
    {
        if (iso == null) return null;
        int tIdx = iso.indexOf('T');
        if (tIdx < 0) return iso;
        String date = iso.substring(0, tIdx);
        String[] parts = hhmmss.split(":");
        if (parts.length < 2) return iso;
        String h = pad2(parts[0]);
        String m = pad2(parts[1]);
        String s = parts.length > 2 ? pad2(parts[2]) : "00";
        return date + "T" + h + ":" + m + ":" + s + ".000";
    }

    /**
     * Apply the original (local − UTC) offset to a new local time to produce
     * the matching new UTC time. Both inputs are ISO local "YYYY-MM-DDTHH:MM:SS.sss"
     * strings parsed as {@link java.time.LocalDateTime}. Returns the new UTC
     * formatted with the same shape.
     */
    private static String recomputeUtc(String origLocal, String origUtc, String newLocal)
    {
        try
        {
            java.time.LocalDateTime ol = parseIsoLocal(origLocal);
            java.time.LocalDateTime ou = parseIsoLocal(origUtc);
            java.time.LocalDateTime nl = parseIsoLocal(newLocal);
            java.time.Duration offset = java.time.Duration.between(ou, ol);
            java.time.LocalDateTime nu = nl.minus(offset);
            return nu.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS"));
        }
        catch (Exception e)
        {
            LOG.debug("recomputeUtc failed for local={} utc={} new={}: {}",
                origLocal, origUtc, newLocal, e.toString());
            return origUtc;
        }
    }

    private static java.time.LocalDateTime parseIsoLocal(String s)
    {
        // SailSys uses both "2026-05-26T18:31:00.000" (millis) and occasionally
        // "2026-05-26T18:31:00" (no millis). Try both.
        try { return java.time.LocalDateTime.parse(s); }
        catch (java.time.format.DateTimeParseException ignored) { }
        if (s != null && s.length() >= 19)
            return java.time.LocalDateTime.parse(s.substring(0, 19));
        throw new java.time.format.DateTimeParseException("Unparseable", s == null ? "" : s, 0);
    }

    private static String pad2(String n)
    {
        String s = n.trim();
        return s.length() >= 2 ? s.substring(0, 2) : ("0" + s).substring(0, 2);
    }

    /**
     * Save per-division earliest start + course length and wait for SailSys to
     * (re)stagger. This is the "Process start times" path for pursuit races —
     * it does the same persist as {@link #handleDivisionStarts} (so the Process
     * button is a save-if-dirty + process in one call) and then polls for the
     * staggered sheet. Body:
     * {@code {divisions: {"<divisionId>": {startTimeLocal: "HH:MM", courseLength: n}}}}.
     * The legacy {@code {courseLength: n}} shape (one length applied to every
     * division, start times untouched) is still accepted. Implementation:
     * <ol>
     *   <li>Fetch the race status to get the current divisionTiming template.</li>
     *   <li>Apply the per-division patch ({@link #patchDivisionTiming}).</li>
     *   <li>PUT it back, which kicks off SailSys-side staggering.</li>
     *   <li>Poll status until handicapAndStartTimeProcessingStatus leaves 1
     *       (= "processing"), up to a 90 s deadline.</li>
     *   <li>Return the final status to the client so the UI can refresh.</li>
     * </ol>
     */
    private void handleProcessRace(HttpServletRequest req, HttpServletResponse resp, int raceId)
        throws Exception
    {
        SailSysSession session = currentSession(req);
        if (session == null)
        {
            resp.setStatus(401);
            writeJson(resp, Map.of("error", "not signed in"));
            return;
        }
        JsonNode body = MAPPER.readTree(req.getInputStream());
        ObjectNode divisions = divisionsFromBody(body);
        boolean legacyGlobalCourse = divisions.isEmpty() && body.path("courseLength").isNumber();
        if (divisions.isEmpty() && !legacyGlobalCourse)
        {
            resp.setStatus(400);
            writeJson(resp, Map.of("error", "divisions object required"));
            return;
        }

        JsonNode status = sailsys.fetchRaceStatus(session.token(), raceId);

        // If SailSys is still staggering from an earlier pass
        // (handicapAndStartTimeProcessingStatus == 1), PUT /timing is rejected
        // with HTTP 400 "Start times are currently being processed". Wait for it
        // to settle (then re-read the fresh divisionTiming) before our own PUT.
        if (status.path("handicapAndStartTimeProcessingStatus").asInt() == 1)
        {
            long preDeadline = System.currentTimeMillis() + 90_000L;
            while (System.currentTimeMillis() < preDeadline
                && status.path("handicapAndStartTimeProcessingStatus").asInt() == 1)
            {
                Thread.sleep(500L);
                status = sailsys.fetchRaceStatus(session.token(), raceId);
            }
            if (status.path("handicapAndStartTimeProcessingStatus").asInt() == 1)
            {
                resp.setStatus(409);
                writeJson(resp, Map.of("error",
                    "SailSys is still processing start times — wait a moment and try again."));
                return;
            }
        }

        JsonNode divisionTiming = status.path("divisionTiming");
        if (!divisionTiming.isArray() || divisionTiming.isEmpty())
        {
            resp.setStatus(400);
            writeJson(resp, Map.of("error", "race has no divisionTiming yet"));
            return;
        }
        ArrayNode patched;
        if (legacyGlobalCourse)
        {
            // Apply one course length to every division (start times untouched).
            double courseLength = body.path("courseLength").asDouble();
            ObjectNode all = MAPPER.createObjectNode();
            for (JsonNode dt : divisionTiming)
                all.putObject(String.valueOf(dt.path("divisionId").asInt()))
                    .put("courseLength", courseLength);
            patched = patchDivisionTiming(divisionTiming, all);
        }
        else
        {
            patched = patchDivisionTiming(divisionTiming, divisions);
        }

        sailsys.setRaceTiming(session.token(), raceId, patched);

        // Poll. processingStatus: 0 = pending, 1 = processing, 2 = processed.
        // We see the transition 0→1→2; if SailSys is fast it can be 0→2 in one
        // tick. Either way, the moment we see anything other than 1 we're done.
        long deadline = System.currentTimeMillis() + 90_000L;
        JsonNode finalStatus = null;
        while (System.currentTimeMillis() < deadline)
        {
            Thread.sleep(500L);
            finalStatus = sailsys.fetchRaceStatus(session.token(), raceId);
            int procStatus = finalStatus.path("handicapAndStartTimeProcessingStatus").asInt();
            if (procStatus != 1) break;
        }
        boolean timedOut = finalStatus != null
            && finalStatus.path("handicapAndStartTimeProcessingStatus").asInt() == 1;

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", !timedOut);
        out.put("raceId", raceId);
        out.put("status", finalStatus);
        if (timedOut) out.put("error", "Processing still in progress after 90s — try refreshing in a moment.");
        writeJson(resp, out);
    }

    /**
     * POST /api/races/{id}/abandon — abandon one or more divisions of a race.
     * Body: {@code {divisions: [<divisionId>, ...]}}; an empty/absent list
     * abandons every division on the race. One-way (SailSys has no un-abandon).
     *
     * <p>Two mechanisms, picked from the fresh race status:
     * <ul>
     *   <li><b>Results not yet processed</b> ({@code lastProcessedTime} empty):
     *       SailSys's own {@code PUT /races/{id}/divisions/abandon} endpoint,
     *       which sets {@code divisionTiming[].isAbandoned=true}.</li>
     *   <li><b>Results already processed</b>: that endpoint is rejected, so we
     *       flag every boat in the target divisions ABD via the
     *       starters/finishers result path ({@link #buildAbandonStarters}).
     *       NOTE: this path is built from error-only HAR captures and is not
     *       yet verified against a successful SailSys round-trip.</li>
     * </ul>
     */
    private void handleAbandon(HttpServletRequest req, HttpServletResponse resp, int raceId)
        throws Exception
    {
        SailSysSession session = currentSession(req);
        if (session == null)
        {
            resp.setStatus(401);
            writeJson(resp, Map.of("error", "not signed in"));
            return;
        }
        String token = session.token();

        JsonNode body = MAPPER.readTree(req.getInputStream());
        java.util.List<Integer> divisionIds = new java.util.ArrayList<>();
        if (body.path("divisions").isArray())
            for (JsonNode d : body.path("divisions")) divisionIds.add(d.asInt());

        JsonNode status = sailsys.fetchRaceStatus(token, raceId);
        // No divisions specified → abandon the whole race (every division).
        if (divisionIds.isEmpty())
            for (JsonNode dt : status.path("divisionTiming"))
                divisionIds.add(dt.path("divisionId").asInt());
        if (divisionIds.isEmpty())
        {
            resp.setStatus(400);
            writeJson(resp, Map.of("error", "race has no divisions to abandon"));
            return;
        }

        boolean resultsProcessed = !status.path("lastProcessedTime").asText("").isEmpty();
        if (!resultsProcessed)
            sailsys.abandonDivisions(token, raceId, divisionIds);
        else
            abandonViaStarters(token, raceId, status, new java.util.HashSet<>(divisionIds));

        JsonNode fresh = sailsys.fetchRaceStatus(token, raceId);
        writeJson(resp, Map.of("ok", true, "raceId", raceId, "status", fresh));
    }

    /**
     * Processed-race abandon: flag every boat in {@code divisionIds} ABD by
     * round-tripping the SailSys result template (GET starters → mutate → PUT
     * starters → PUT finishers), mirroring {@link #handlePushResults}'s
     * token-chained two-PUT flow.
     */
    private void abandonViaStarters(String token, int raceId, JsonNode status,
                                    java.util.Set<Integer> divisionIds) throws Exception
    {
        int saveToken = status.path("resultSaveToken").asInt(0);
        String raceDate = status.path("dateTime").asText("");
        raceDate = (raceDate.length() >= 10) ? raceDate.substring(0, 10)
            : java.time.LocalDate.now().toString();

        JsonNode startersNode = sailsys.fetchRaceStarters(token, raceId);
        if (!startersNode.isArray() || startersNode.isEmpty())
            throw new SailSysClient.SailSysException("abandonViaStarters", "GET",
                "/races/" + raceId + "/results/starters", 502,
                "no starters template to flag ABD");

        // ABD penalty object per target division (penalty ids are per-division).
        java.util.Map<Integer, JsonNode> abdByDivision = new java.util.HashMap<>();
        for (Integer divId : divisionIds)
        {
            JsonNode abd = findPenaltyByShortName(
                sailsys.fetchDivisionPenalties(token, divId), "ABD");
            if (abd != null) abdByDivision.put(divId, abd);
        }

        ArrayNode payload = buildAbandonStarters(startersNode, divisionIds, abdByDivision, raceDate);
        JsonNode afterStarters = sailsys.putRaceStarters(token, raceId, saveToken, payload);
        int newToken = afterStarters.path("resultSaveToken").asInt(saveToken + 1);
        Thread.sleep(300L);
        sailsys.putRaceFinishers(token, raceId, newToken, payload);
    }

    /**
     * Build the {@code /results/starters} payload that flags every boat in the
     * given divisions ABD: {@code startedRace=true}, {@code finishTime} cleared,
     * {@code finishDate} set to the race date, and the division's ABD penalty
     * attached. Boats in other divisions are left exactly as the template had
     * them. Does not mutate the input. Pure logic — unit-tested in
     * {@code ApiServletTest}.
     *
     * @param startersTemplate the {@code /results/starters} array (full echo)
     * @param divisionIds      divisions to abandon
     * @param abdByDivision    division id → that division's ABD penalty object
     * @param raceDate         {@code yyyy-MM-dd}, used for {@code finishDate}
     */
    static ArrayNode buildAbandonStarters(JsonNode startersTemplate,
                                          java.util.Set<Integer> divisionIds,
                                          java.util.Map<Integer, JsonNode> abdByDivision,
                                          String raceDate)
    {
        ArrayNode out = (ArrayNode) startersTemplate.deepCopy();
        for (JsonNode entry : out)
        {
            if (!(entry instanceof ObjectNode obj)) continue;
            int divisionId = obj.path("divisionId").asInt();
            if (!divisionIds.contains(divisionId)) continue;
            obj.put("startedRace", true);
            obj.putNull("finishTime");
            obj.put("finishDate", raceDate + "T00:00:00.000");
            ArrayNode penalties = MAPPER.createArrayNode();
            JsonNode abd = abdByDivision.get(divisionId);
            if (abd != null) penalties.add(abd);
            obj.set("penalties", penalties);
        }
        return out;
    }

    // ---- Race-duration → per-division course-length calculator ----------------

    /** A division's slowest-boat TCF, as supplied by the client. */
    record DivisionTcf(int divisionId, double slowestTcf) {}

    /** A division's computed course length (nm, 1 decimal). */
    record DivisionCourse(int divisionId, double courseLengthNm) {}

    /** Result of {@link #computeCoursePlan}: the (possibly capped) duration and the per-division courses. */
    record CoursePlan(int effectiveDurationMinutes, boolean limitedBySunset, List<DivisionCourse> divisions) {}

    /**
     * Turn a target race duration into a per-division course length. A boat's
     * predicted speed is {@code TCF × v0Knots}, so over {@code t} hours it sails
     * {@code TCF × v0 × t} nm; each division's course is sized to the slowest
     * (min-TCF) boat — supplied per division by the caller — rounded to 0.1 nm.
     *
     * <p>When {@code limitBySunset} and a {@code sunset} are given, the duration
     * is capped so {@code earliestStart + duration ≤ sunset}. If sunset is at or
     * before {@code earliestStart} (an out-of-season date where it's already
     * dark at the start), the cap still engages — clamped to 0 minutes and
     * flagged — rather than silently doing nothing. Pure logic — unit-tested in
     * {@code ApiServletTest}.
     */
    static CoursePlan computeCoursePlan(double v0Knots, int requestedDurationMinutes,
                                        boolean limitBySunset, LocalTime earliestStart,
                                        LocalTime sunset, List<DivisionTcf> divisions)
    {
        int effective = requestedDurationMinutes;
        boolean limited = false;
        if (limitBySunset && sunset != null && earliestStart != null)
        {
            long maxMinutes = java.time.Duration.between(earliestStart, sunset).toMinutes();
            if (effective > maxMinutes)
            {
                effective = (int) Math.max(0, maxMinutes);
                limited = true;
            }
        }
        double hours = effective / 60.0;
        List<DivisionCourse> out = new ArrayList<>(divisions.size());
        for (DivisionTcf d : divisions)
        {
            double nm = d.slowestTcf() * v0Knots * hours;
            double rounded = Math.round(nm * 10.0) / 10.0;
            out.add(new DivisionCourse(d.divisionId(), rounded));
        }
        return new CoursePlan(effective, limited, out);
    }

    /**
     * Calibration probe: discover how SailSys converts (courseLength, TCF)
     * into per-boat start-time offsets for a pursuit race.
     *
     * <p>Admin-only. Mutates SailSys state, then restores it in a finally
     * block. Flow:
     * <ol>
     *   <li>Snapshot current divisionTiming + entrants/start-times
     *       visibility flags.</li>
     *   <li>Hide both visibilities so the calibration probe doesn't leak
     *       to public spectators mid-flight.</li>
     *   <li>PUT calibration timing: every division gets
     *       {@code courseLength=100} (nm) and
     *       {@code startTimeLocal=raceDate@10:00:00} with the matching
     *       {@code startTimeUtc} computed from the original local→UTC
     *       offset.</li>
     *   <li>Wait for SailSys-side start-time processing.</li>
     *   <li>Fetch entrants — each carries the SailSys-computed
     *       per-boat {@code startTimeLocal} for this calibration setup.
     *       Build a per-division view with TCF + start time + offset
     *       from the earliest start in the division.</li>
     *   <li>Restore divisionTiming to its original values (PUT + wait).
     *       Restore visibility flags. All restore steps run inside a
     *       finally block so a mid-flight failure still attempts to
     *       roll back.</li>
     * </ol>
     *
     * <p>Per-division output sorts entrants by TCF descending (slowest
     * first), with {@code startOffsetSeconds} measured from the earliest
     * (typically slowest) boat's start. Reading the offset-vs-TCF
     * relationship reveals the speed/length conversion SailSys applies.
     */
    private void handleCalibrate(HttpServletRequest req, HttpServletResponse resp, int raceId)
        throws Exception
    {
        SailSysSession session = currentSession(req);
        if (session == null)
        {
            resp.setStatus(401);
            writeJson(resp, Map.of("error", "not signed in"));
            return;
        }
        if (adminLevelForClub(session.user(), config.sailsys().clubId()) != 0)
        {
            resp.setStatus(403);
            writeJson(resp, Map.of("error", "admin required"));
            return;
        }
        String token = session.token();

        // 1. Snapshot original state.
        JsonNode status = sailsys.fetchRaceStatus(token, raceId);
        JsonNode origDivisionTiming = status.path("divisionTiming");
        if (!origDivisionTiming.isArray() || origDivisionTiming.isEmpty())
        {
            resp.setStatus(400);
            writeJson(resp, Map.of("error", "race has no divisionTiming to calibrate"));
            return;
        }
        int origEntrantsVis = status.path("raceEntrantVisibility").asInt(0);
        int origStartTimesVis = status.path("handicapAndStartTimeVisibility").asInt(0);
        String raceDate = status.path("dateTime").asText("");
        if (raceDate.length() >= 10) raceDate = raceDate.substring(0, 10);
        else raceDate = java.time.LocalDate.now().toString();

        Map<String, Object> calibration = null;
        Exception failure = null;
        // Track the "fresh processing completed" marker. SailSys bumps
        // startTimeLastProcessedTime after each /timing PUT settles. We
        // capture it before each PUT so the wait helper can detect a
        // genuine new completion (status==2 AND timestamp moved) rather
        // than racing with the pre-PUT processed=2 state.
        String preCalProcessedTime = status.path("startTimeLastProcessedTime").asText("");
        try
        {
            // 2. Hide visibilities (only if currently visible — preserves
            //    "already hidden" state on restore).
            if (origEntrantsVis == 1)
                sailsys.setEntrantsVisibility(token, raceId, false);
            if (origStartTimesVis == 1)
                sailsys.setStartTimesVisibility(token, raceId, false);

            // 3. Build calibration divisionTiming. Course 100nm, start
            //    10:00:00 on the race date. UTC computed via the same
            //    helper as the user-driven start time edits.
            String calStartLocal = raceDate + "T10:00:00.000";
            JsonNode calibTiming = origDivisionTiming.deepCopy();
            for (JsonNode dt : calibTiming)
            {
                if (!(dt instanceof ObjectNode on)) continue;
                String origLocal = on.path("startTimeLocal").asText("");
                String origUtc = on.path("startTimeUtc").asText("");
                on.put("courseLength", 100.0);
                on.put("startTimeLocal", calStartLocal);
                on.put("startTimeUtc", recomputeUtc(origLocal, origUtc, calStartLocal));
            }
            sailsys.setRaceTiming(token, raceId, calibTiming);

            // 4. Wait for SailSys to actually finish processing — must
            //    see status==2 AND a fresh startTimeLastProcessedTime.
            if (!waitForFreshStartTimeProcessing(token, raceId,
                    preCalProcessedTime, 120_000L))
                throw new IllegalStateException(
                    "SailSys did not finish processing calibration timing within 2 min");

            // 5. Read back the per-boat staggered start times.
            JsonNode entrants = sailsys.fetchRaceEntrants(token, raceId);
            calibration = buildCalibrationView(entrants, origDivisionTiming, calStartLocal);
        }
        catch (Exception e)
        {
            LOG.warn("Calibration failed for race {}: {}", raceId, e.toString());
            failure = e;
        }
        finally
        {
            // 6. Restore divisionTiming + visibility — best effort, log
            //    on failure so a partial restore is at least diagnosable.
            //    The PUT here re-triggers SailSys start-time processing
            //    (every successful /timing PUT does), so when this wait
            //    returns the race's per-boat start times will reflect the
            //    original courseLength + start gun, not the calibration
            //    values. Capture the "before" timestamp from a fresh
            //    status fetch so the wait detects this restore's
            //    completion rather than the calibration's.
            try
            {
                JsonNode preRestoreStatus = sailsys.fetchRaceStatus(token, raceId);
                String preRestoreProcessedTime = preRestoreStatus
                    .path("startTimeLastProcessedTime").asText("");
                sailsys.setRaceTiming(token, raceId, origDivisionTiming);
                if (!waitForFreshStartTimeProcessing(token, raceId,
                        preRestoreProcessedTime, 120_000L))
                    LOG.warn("Calibration timing restore did not finish " +
                        "processing for race {} within 2 min — leaving the " +
                        "polling to settle; race state may be transient.",
                        raceId);
            }
            catch (Exception e)
            {
                LOG.error("Calibration timing restore failed for race {}: {}",
                    raceId, e.toString());
            }
            try
            {
                if (origEntrantsVis == 1)
                    sailsys.setEntrantsVisibility(token, raceId, true);
            }
            catch (Exception e)
            {
                LOG.error("Calibration entrants-visibility restore failed: {}", e.toString());
            }
            try
            {
                if (origStartTimesVis == 1)
                    sailsys.setStartTimesVisibility(token, raceId, true);
            }
            catch (Exception e)
            {
                LOG.error("Calibration start-times-visibility restore failed: {}", e.toString());
            }
        }

        if (failure != null)
        {
            resp.setStatus(500);
            writeJson(resp, Map.of("error", "Calibration failed: " + failure.getMessage()));
            return;
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("raceId", raceId);
        out.put("calibration", calibration);
        writeJson(resp, out);
    }

    /**
     * Poll {@code /races/{id}/status} until SailSys has finished a
     * <em>fresh</em> start-time processing pass — i.e.
     * {@code handicapAndStartTimeProcessingStatus == 2 (processed)} AND
     * {@code startTimeLastProcessedTime} has changed from the captured
     * pre-PUT value. Both conditions matter:
     * <ul>
     *   <li>SailSys takes a moment to flip status from the prior
     *       {@code 2} into {@code 0/1} after a PUT; polling on
     *       status alone can return prematurely against the stale
     *       {@code 2}.</li>
     *   <li>{@code startTimeLastProcessedTime} only updates when a
     *       processing pass actually completes, so a fresh timestamp is
     *       the authoritative "we're done" signal.</li>
     * </ul>
     * Returns true on a fresh completion, false on timeout.
     */
    private boolean waitForFreshStartTimeProcessing(String token, int raceId,
                                                    String previousProcessedTime,
                                                    long timeoutMs) throws Exception
    {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline)
        {
            Thread.sleep(500L);
            JsonNode s = sailsys.fetchRaceStatus(token, raceId);
            int proc = s.path("handicapAndStartTimeProcessingStatus").asInt(2);
            String now = s.path("startTimeLastProcessedTime").asText("");
            if (proc == 2 && !now.isEmpty() && !now.equals(previousProcessedTime))
                return true;
        }
        return false;
    }

    /**
     * Group entrants by division and assemble the calibration view: per
     * division, a sorted list of entrants (descending TCF — slowest
     * first) with their TCF, computed startTimeLocal, and offset (in
     * seconds) from the earliest start in that division.
     */
    private Map<String, Object> buildCalibrationView(JsonNode entrants,
                                                     JsonNode origDivisionTiming,
                                                     String calStartLocal)
    {
        Map<Integer, List<Map<String, Object>>> byDivision = new LinkedHashMap<>();
        if (entrants != null && entrants.isArray())
        {
            for (JsonNode e : entrants)
            {
                int divId = e.path("division").path("id").asInt(0);
                int boatId = e.path("boat").path("id").asInt(0);
                if (boatId == 0) continue;
                String sail = e.path("boat").path("sailNumber").asText("");
                String name = e.path("boat").path("name").asText("");
                String startTimeLocal = e.path("startTimeLocal").asText("");
                double tcf = 0;
                JsonNode hc = e.path("handicap").path("currentHandicaps");
                if (hc.isArray() && hc.size() > 0)
                    tcf = hc.get(0).path("value").asDouble(0);
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("boatId", boatId);
                row.put("sailNumber", sail);
                row.put("name", name);
                row.put("tcf", tcf);
                row.put("startTimeLocal", startTimeLocal);
                byDivision.computeIfAbsent(divId, k -> new ArrayList<>()).add(row);
            }
        }

        // Resolve division names from origDivisionTiming so the response
        // is self-describing.
        Map<Integer, String> divisionNames = new HashMap<>();
        for (JsonNode dt : origDivisionTiming)
        {
            int id = dt.path("divisionId").asInt(0);
            if (id != 0) divisionNames.put(id, dt.path("divisionName").asText(""));
        }

        List<Map<String, Object>> divisionsOut = new ArrayList<>();
        for (var entry : byDivision.entrySet())
        {
            int divId = entry.getKey();
            List<Map<String, Object>> rows = entry.getValue();
            // Find earliest start in this division (in seconds).
            long earliest = Long.MAX_VALUE;
            for (var r : rows)
            {
                long t = parseTimeOfDaySeconds((String) r.get("startTimeLocal"));
                if (t >= 0 && t < earliest) earliest = t;
            }
            // Annotate each row with the offset from earliest.
            for (var r : rows)
            {
                long t = parseTimeOfDaySeconds((String) r.get("startTimeLocal"));
                r.put("startOffsetSeconds", (t < 0 || earliest == Long.MAX_VALUE) ? -1 : (t - earliest));
            }
            // Sort by TCF descending (slowest first — they start earliest).
            rows.sort((a, b) -> Double.compare(
                ((Number) b.get("tcf")).doubleValue(),
                ((Number) a.get("tcf")).doubleValue()));
            Map<String, Object> divOut = new LinkedHashMap<>();
            divOut.put("divisionId", divId);
            divOut.put("divisionName", divisionNames.getOrDefault(divId, ""));
            divOut.put("entrants", rows);
            divisionsOut.add(divOut);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        double courseLengthNm = 100.0;
        out.put("courseLength", courseLengthNm);
        out.put("earliestStart", "10:00:00");
        out.put("divisions", divisionsOut);
        out.put("derivation", deriveV0(divisionsOut, courseLengthNm));
        return out;
    }

    /**
     * Solve for V₀ — the speed at which SailSys assumes a 1.000-TCF boat sails:
     * <pre>
     *   v0 [kn] = D [nm] / Δt [h] × (1/TCF_slow − 1/TCF_fast)
     * </pre>
     * <p><strong>Within a single division.</strong> SailSys staggers each boat
     * relative to the earliest (slowest) boat <em>in its own division</em>, so
     * {@code startOffsetSeconds} is only comparable between boats in the same
     * division. We therefore compute V₀ from each division's slowest/fastest
     * pair and keep the one with the widest offset spread (best precision);
     * pairing a slow boat in one division with a fast boat in another mixes
     * incompatible reference points and inflates V₀.
     *
     * <p>Returns the chosen pair + result so the UI can render the working and
     * Save can persist exactly what the user saw. {@code null} when no single
     * division yielded two distinct, offset-bearing TCFs.
     */
    static Map<String, Object> deriveV0(List<Map<String, Object>> divisionsOut,
                                        double courseLengthNm)
    {
        Map<String, Object> best = null;
        long bestDelta = 0;
        for (Map<String, Object> div : divisionsOut)
        {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> rows = (List<Map<String, Object>>) div.get("entrants");
            if (rows == null) continue;

            // Slowest (min TCF) and fastest (max TCF) offset-bearing boats in
            // THIS division only.
            Map<String, Object> slowest = null;
            Map<String, Object> fastest = null;
            for (Map<String, Object> r : rows)
            {
                Object offObj = r.get("startOffsetSeconds");
                Object tcfObj = r.get("tcf");
                if (!(offObj instanceof Number offN) || !(tcfObj instanceof Number tcfN))
                    continue;
                if (offN.longValue() < 0 || tcfN.doubleValue() <= 0) continue;
                if (slowest == null || tcfN.doubleValue() < ((Number) slowest.get("tcf")).doubleValue())
                    slowest = r;
                if (fastest == null || tcfN.doubleValue() > ((Number) fastest.get("tcf")).doubleValue())
                    fastest = r;
            }
            if (slowest == null || fastest == null || slowest == fastest) continue;

            long slowestOff = ((Number) slowest.get("startOffsetSeconds")).longValue();
            long fastestOff = ((Number) fastest.get("startOffsetSeconds")).longValue();
            long deltaSeconds = fastestOff - slowestOff;
            if (deltaSeconds <= 0) continue;

            // Keep the division with the largest spread — most boats / least
            // relative rounding error in SailSys's minute-quantised offsets.
            if (deltaSeconds <= bestDelta) continue;
            bestDelta = deltaSeconds;

            double slowestTcf = ((Number) slowest.get("tcf")).doubleValue();
            double fastestTcf = ((Number) fastest.get("tcf")).doubleValue();
            double v0 = courseLengthNm / (deltaSeconds / 3600.0) * (1.0 / slowestTcf - 1.0 / fastestTcf);

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("courseLengthNm", courseLengthNm);
            out.put("slowestTcf", slowestTcf);
            out.put("slowestStartOffsetSeconds", slowestOff);
            out.put("fastestTcf", fastestTcf);
            out.put("fastestStartOffsetSeconds", fastestOff);
            out.put("deltaSeconds", deltaSeconds);
            out.put("v0Knots", v0);
            if (div.get("divisionId") != null) out.put("divisionId", div.get("divisionId"));
            if (div.get("divisionName") != null) out.put("divisionName", div.get("divisionName"));
            best = out;
        }
        return best;
    }

    /**
     * Extract HH:MM[:SS] from an ISO local datetime (or any string containing
     * it after a {@code T}, space, or start-of-string) and return the total
     * seconds-from-midnight. Seconds default to zero when absent — SailSys
     * has been observed returning per-entrant {@code startTimeLocal} values
     * with minute-only precision (e.g. {@code 2026-05-29T16:06}), and a
     * stricter regex would silently fail the whole calibration probe.
     * Returns -1 if no time can be extracted.
     */
    private static long parseTimeOfDaySeconds(String iso)
    {
        if (iso == null) return -1;
        java.util.regex.Matcher m = java.util.regex.Pattern
            .compile("(?:^|[T\\s])(\\d{1,2}):(\\d{1,2})(?::(\\d{1,2}))?").matcher(iso);
        if (!m.find()) return -1;
        long ss = (m.group(3) != null) ? Long.parseLong(m.group(3)) : 0L;
        return Long.parseLong(m.group(1)) * 3600L
             + Long.parseLong(m.group(2)) * 60L
             + ss;
    }

    /**
     * Read the RO-captured times for a race. Returns {@code {raceId, times:null}}
     * (with {@code times:null}) when nothing has been saved yet, so the client
     * can distinguish "never saved" from "saved empty".
     *
     * <p>TODO: SailSys is the source of truth for {@code came} and {@code finish}
     * (they're written to /races/{id}/results/starters and /finishers on SAVE).
     * Loaders should fetch /races/{id}/results/finishers from SailSys and merge
     * its {@code startedRace} and {@code finishTime} over the values in this
     * JSON — currently the local file's came/finish fields can shadow the
     * SailSys state if the two diverge (e.g. an edit made directly in SailSys
     * after a jinx SAVE). The JSON should ultimately hold only the
     * actualStart times, dutyBoatId, and boatOrder.
     */
    private void handleGetRaceTimes(HttpServletResponse resp, String raceId) throws IOException
    {
        RaceTimes times = store.raceTimes(raceId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("raceId", raceId);
        out.put("times", times);
        writeJson(resp, out);
    }

    /**
     * Persist the RO-captured times for a race. Body is a full {@link RaceTimes}
     * payload (raceId, boatOrder, times) — replaces whatever was on disk.
     */
    private void handleSaveRaceTimes(HttpServletRequest req, HttpServletResponse resp, String raceId)
        throws IOException
    {
        RaceTimes incoming = MAPPER.readValue(req.getInputStream(), RaceTimes.class);
        // Normalise: trust the URL's raceId over whatever the body claimed.
        RaceTimes toStore = new RaceTimes(raceId, incoming.boatOrder(),
            incoming.dutyBoatId(), incoming.divisionStarts(), incoming.times());
        store.putRaceTimes(raceId, toStore);
        writeJson(resp, Map.of("ok", true, "raceId", raceId));
    }

    private Map<String, Object> authStatus(HttpServletRequest req)
    {
        SailSysSession session = currentSession(req);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("authenticated", session != null);
        if (session != null)
        {
            out.put("user", userSummary(session));
            out.put("loginTime", session.loginTime().toString());
        }
        return out;
    }

    private Map<String, Object> publicConfig()
    {
        JinxConfig.SailSys s = config.sailsys();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sailsys", Map.of(
            "clubId", s.clubId(),
            "handicapDefinitionId", s.handicapDefinitionId(),
            "timezone", s.timezone()));
        out.put("algorithm", algorithmMap(config.algorithm()));
        return out;
    }

    // --- helpers ---

    /** Returns the active SailSysSession, or null if the caller is not logged in. */
    static SailSysSession currentSession(HttpServletRequest req)
    {
        HttpSession httpSession = req.getSession(false);
        if (httpSession == null)
            return null;
        Object attr = httpSession.getAttribute(SESSION_ATTR);
        return (attr instanceof SailSysSession s) ? s : null;
    }

    private Map<String, Object> userSummary(SailSysSession session)
    {
        SailSysClient.User u = session.user();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("email", u.email);
        out.put("firstname", u.firstname);
        out.put("surname", u.surname);
        // adminLevel for the configured clubId — 0 = admin (can edit series
        // and TCFs), 1 = race officer (can manage individual races but not
        // series/handicaps). The discriminator is observed from HAR-9: greg
        // (handicapper) has adminLevel=0 for MYC; raceofficer@... has
        // adminLevel=1. Race-officer requests to /series/{id} return 403,
        // so the client must avoid those endpoints for non-admins.
        int level = adminLevelForClub(u, config.sailsys().clubId());
        out.put("adminLevel", level);
        out.put("isAdmin", level == 0);
        return out;
    }

    /**
     * POST /api/races/{id}/course-plan — turn a target race duration into a
     * per-division course length for a pursuit race. Body shape:
     * <pre>{@code
     *   { "seriesId": "5699",          // optional; selects per-series config overlay
     *     "raceDate": "2026-01-13",    // optional; only needed when limitBySunset
     *     "durationMinutes": 90,        // optional; defaults to series idealRaceDuration
     *     "divisions": [ { "divisionId": 13779, "slowestTcf": 0.95 }, ... ] }
     * }</pre>
     *
     * <p>The client supplies each division's slowest (min) TCF — so unsaved TCF
     * edits are honoured and no SailSys round-trip is needed. The server applies
     * the saved calibration's V₀, the per-series sunset cap (when configured),
     * and {@link #computeCoursePlan}. Returns the effective (possibly capped)
     * duration, the sunset cutoff, and per-division course lengths (0.1 nm).
     */
    private void handleCoursePlan(HttpServletRequest req, HttpServletResponse resp, String raceId)
        throws Exception
    {
        SailSysSession session = currentSession(req);
        if (session == null)
        {
            resp.setStatus(401);
            writeJson(resp, Map.of("error", "not signed in"));
            return;
        }

        // Same gate as Process Handicaps: the TCF → course conversion needs V₀.
        org.mortbay.sailing.jinx.model.Calibration calibration = store.calibration();
        if (calibration == null)
        {
            resp.setStatus(409);
            writeJson(resp, Map.of("error",
                "No calibration on file. Run Calibrate first to measure "
                + "SailSys's TCF → start-offset conversion (V₀)."));
            return;
        }

        JsonNode body = MAPPER.readTree(req.getInputStream());

        String seriesId = body.path("seriesId").isMissingNode()
            ? null : body.path("seriesId").asText(null);
        JinxConfig.Algorithm alg = (seriesId != null) ? store.seriesConfig(seriesId) : null;
        if (alg == null) alg = config.algorithm();

        int duration = (body.path("durationMinutes").isMissingNode() || body.path("durationMinutes").isNull())
            ? alg.idealRaceDuration()
            : body.path("durationMinutes").asInt(alg.idealRaceDuration());
        if (duration <= 0) duration = alg.idealRaceDuration();

        List<DivisionTcf> divisions = new ArrayList<>();
        for (JsonNode d : body.path("divisions"))
        {
            if (d.path("divisionId").isMissingNode()) continue;
            double tcf = d.path("slowestTcf").asDouble(0);
            if (!(tcf > 0)) continue; // a division with no valid TCF can't be sized
            divisions.add(new DivisionTcf(d.path("divisionId").asInt(), tcf));
        }

        LocalTime earliestStart = null;
        try { earliestStart = LocalTime.parse(alg.earliestStart()); }
        catch (Exception ignore) { /* leave null → no sunset cap */ }

        LocalTime sunset = null;
        if (alg.limitBySunset() && earliestStart != null)
        {
            String raceDate = body.path("raceDate").asText(null);
            if (raceDate != null && raceDate.length() >= 10
                && alg.latitude() != null && alg.longitude() != null)
            {
                try
                {
                    sunset = SolarTimes.sunsetLocal(alg.latitude(), alg.longitude(),
                        java.time.LocalDate.parse(raceDate.substring(0, 10)),
                        java.time.ZoneId.of(config.sailsys().timezone()));
                }
                catch (Exception e)
                {
                    LOG.warn("Sunset computation failed for course-plan (race {}): {}",
                        raceId, e.toString());
                }
            }
        }

        CoursePlan plan = computeCoursePlan(calibration.v0Knots(), duration,
            alg.limitBySunset(), earliestStart, sunset, divisions);

        List<Map<String, Object>> divOut = new ArrayList<>(plan.divisions().size());
        for (DivisionCourse dc : plan.divisions())
            divOut.add(Map.of("divisionId", dc.divisionId(), "courseLengthNm", dc.courseLengthNm()));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("raceId", raceId);
        out.put("requestedDurationMinutes", duration);
        out.put("effectiveDurationMinutes", plan.effectiveDurationMinutes());
        out.put("limitedBySunset", plan.limitedBySunset());
        out.put("sunsetLocal", sunset == null ? null
            : String.format("%02d:%02d", sunset.getHour(), sunset.getMinute()));
        out.put("v0Knots", calibration.v0Knots());
        out.put("divisions", divOut);
        writeJson(resp, out);
    }

    /**
     * POST /api/races/{id}/process-handicaps — run the Jinx algorithm against
     * a client-supplied snapshot of the race. Body shape:
     * <pre>{@code
     *   { "seriesId": "5699",                  // optional; selects per-series config overlay
     *     "targetElapsedMinutes": 90,           // optional; defaults to series idealRaceDuration
     *     "boats": [
     *       { "boatId": "...", "currentTcf": 1.0, "status": "FIN",
     *         "elapsedMinutes": 85.0 },
     *       { "boatId": "...", "currentTcf": 1.0, "status": "DNF" },
     *       ...
     *     ] }
     * }</pre>
     *
     * <p>The endpoint is read-only — it computes adjustments in memory and
     * returns them. Persistence happens via the SAVE endpoint below so the
     * admin can preview before committing.
     */
    private void handleProcessHandicaps(HttpServletRequest req, HttpServletResponse resp, String raceId)
        throws Exception
    {
        // Gate: the V₀-based TCF projection requires a saved calibration. The
        // UI mirrors this — Process Handicaps is only enabled when GET
        // /api/calibration returned non-null on page load.
        org.mortbay.sailing.jinx.model.Calibration calibration = store.calibration();
        if (calibration == null)
        {
            resp.setStatus(409);
            writeJson(resp, Map.of("error",
                "No calibration on file. Run Calibrate first to measure "
                + "SailSys's TCF → start-offset conversion (V₀)."));
            return;
        }

        JsonNode body = MAPPER.readTree(req.getInputStream());

        String seriesId = body.path("seriesId").isMissingNode()
            ? null : body.path("seriesId").asText(null);
        JinxConfig.Algorithm alg = (seriesId != null) ? store.seriesConfig(seriesId) : null;
        if (alg == null) alg = config.algorithm();

        int tTarget = body.path("targetElapsedMinutes").asInt(alg.idealRaceDuration());

        JsonNode boatsNode = body.path("boats");
        if (!boatsNode.isArray() || boatsNode.isEmpty())
        {
            resp.setStatus(400);
            writeJson(resp, Map.of("error", "boats array required"));
            return;
        }

        List<Boat> boats = new ArrayList<>(boatsNode.size());
        Map<String, Result> results = new LinkedHashMap<>(boatsNode.size());
        for (JsonNode b : boatsNode)
        {
            String boatId = b.path("boatId").asText();
            if (boatId == null || boatId.isBlank()) continue;
            double tcf = b.path("currentTcf").asDouble(1.0);
            boats.add(new Boat(boatId, "", "", "", "", tcf));
            FinishStatus status;
            try
            {
                status = FinishStatus.valueOf(b.path("status").asText("DNC").toUpperCase());
            }
            catch (IllegalArgumentException ex)
            {
                status = FinishStatus.DNC;
            }
            // Engine reads elapsed via Result.elapsed() = finish − actualStart.
            // We don't have wall-clock times here — only the precomputed elapsed
            // minutes. Encode it as actualStart=00:00 and finish=elapsed past
            // midnight so the Duration math comes out right.
            java.time.LocalTime startT = java.time.LocalTime.MIDNIGHT;
            java.time.LocalTime finishT = null;
            if (status == FinishStatus.FIN && b.has("elapsedMinutes"))
            {
                double mins = b.path("elapsedMinutes").asDouble(0);
                long secs = Math.round(mins * 60.0);
                finishT = startT.plusSeconds(secs);
            }
            // Authoritative finishPosition supplied by the client (pursuit
            // races: local finish-order computation; scratch starts:
            // SailSys's place when clean). When omitted the engine falls
            // back to elapsed-sort which is wrong for OCS scenarios.
            Integer finishPosition = b.has("finishPosition") && !b.path("finishPosition").isNull()
                ? b.path("finishPosition").asInt()
                : null;
            results.put(boatId, new Result(boatId, status, startT, finishT, null, finishPosition));
        }

        Race race = new Race(raceId, 0, "", null, tTarget, null, RaceStatus.RESULTS_ENTERED);
        PursuitHandicapEngine engine = new PursuitHandicapEngine(alg, calibration);
        List<Adjustment> adjustments = engine.processResults(boats, race, results);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("raceId", raceId);
        out.put("seriesId", seriesId);
        out.put("targetElapsedMinutes", tTarget);
        out.put("config", Map.of(
            "penaltyList", alg.penaltyList(),
            "idealRaceDuration", alg.idealRaceDuration(),
            "dnfAllowance", alg.dnfAllowance(),
            "earliestStart", alg.earliestStart()));
        out.put("calibration", Map.of("v0Knots", calibration.v0Knots()));
        out.put("adjustments", adjustments);
        writeJson(resp, out);
    }

    /**
     * POST /api/races/{id}/save-handicaps — persist the Jinx-computed
     * adjustments locally AND write the next race's TCF snapshot from
     * {@link Adjustment#newTcf()}. The push to SailSys does not happen here:
     * the admin sees the new TCFs surfaced as a banner the next time they
     * open race N+1, with Push / Reset buttons.
     *
     * <p>Body: {@code {adjustments:[...], seriesId:"5699"}}. {@code seriesId}
     * is required so we can locate the next race in the series without an
     * extra SailSys round-trip on the read side.
     */
    private void handleSaveHandicaps(HttpServletRequest req, HttpServletResponse resp, String raceId)
        throws Exception
    {
        SailSysSession session = currentSession(req);
        if (session == null)
        {
            resp.setStatus(401);
            writeJson(resp, Map.of("error", "not signed in"));
            return;
        }
        if (adminLevelForClub(session.user(), config.sailsys().clubId()) != 0)
        {
            resp.setStatus(403);
            writeJson(resp, Map.of("error", "admin required"));
            return;
        }
        JsonNode body = MAPPER.readTree(req.getInputStream());
        JsonNode arr = body.isArray() ? body : body.path("adjustments");
        if (!arr.isArray())
        {
            resp.setStatus(400);
            writeJson(resp, Map.of("error", "adjustments array required"));
            return;
        }
        List<Adjustment> adjustments = MAPPER.convertValue(arr, new TypeReference<List<Adjustment>>() { });
        store.putPendingAdjustments(raceId, adjustments);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        result.put("raceId", raceId);
        result.put("saved", adjustments.size());

        // Find race N+1 and write its TCF snapshot from Adjustment.newTcf.
        // seriesId comes from the request body but is informational only —
        // nextRaceId is read directly from race N's status payload. The
        // same status fetch supplies race N's own number, which is stored
        // in the snapshot so the banner on race N+1 can say "after race 1"
        // instead of "after race 41476". Skip silently if no next race
        // exists (last race in the series).
        try
        {
            JsonNode sourceStatus = sailsys.fetchRaceStatus(session.token(), Integer.parseInt(raceId));
            JsonNode nextNode = sourceStatus.path("nextRaceId");
            String nextRaceId = (nextNode.isMissingNode() || nextNode.isNull())
                ? null : nextNode.asText(null);
            if (nextRaceId != null && !nextRaceId.isBlank() && !"null".equals(nextRaceId))
            {
                Integer sourceRaceNumber = sourceStatus.path("number").isMissingNode()
                    || sourceStatus.path("number").isNull()
                    ? null : sourceStatus.path("number").asInt();
                RaceTcfSnapshot snap = buildSnapshotFromAdjustments(
                    session.token(), nextRaceId, raceId, sourceRaceNumber, adjustments);
                store.putRaceTcfs(nextRaceId, snap);
                result.put("nextRaceId", nextRaceId);
                result.put("snapshotTcfs", snap.tcfs().size());
            }
        }
        catch (Exception e)
        {
            LOG.warn("Failed to write N+1 TCF snapshot for race {}: {}",
                raceId, e.toString());
            result.put("nextRaceSnapshotError", e.getMessage());
        }
        writeJson(resp, result);
    }

    /**
     * Builds a TCF snapshot for {@code nextRaceId} from a list of
     * adjustments. {@code spinnakerType} is read from the next race's
     * existing entrants (so the saved snapshot is complete enough to push
     * back to SailSys later); boats present in the adjustments but missing
     * from the next race's entrants are skipped. {@code sourceRaceNumber}
     * is the human-friendly race number of the source race (e.g. 1, 2, …);
     * may be null.
     */
    private RaceTcfSnapshot buildSnapshotFromAdjustments(String token, String nextRaceId,
                                                         String sourceRaceId,
                                                         Integer sourceRaceNumber,
                                                         List<Adjustment> adjustments) throws Exception
    {
        JsonNode entrants = sailsys.fetchRaceEntrants(token, Integer.parseInt(nextRaceId));
        Map<String, Integer> spinByBoat = new HashMap<>();
        if (entrants.isArray())
        {
            int defId = config.sailsys().handicapDefinitionId();
            for (JsonNode e : entrants)
            {
                String boatId = e.path("boat").path("id").asText(null);
                if (boatId == null) continue;
                for (JsonNode h : e.path("handicap").path("currentHandicaps"))
                {
                    if (h.path("definition").path("id").asInt() == defId)
                    {
                        spinByBoat.put(boatId, h.path("spinnakerType").asInt(1));
                        break;
                    }
                }
            }
        }
        List<RaceTcfSnapshot.TcfEntry> tcfs = new ArrayList<>();
        for (Adjustment a : adjustments)
        {
            Integer spin = spinByBoat.get(a.boatId());
            if (spin == null) continue;
            tcfs.add(new RaceTcfSnapshot.TcfEntry(a.boatId(), a.newTcf(), spin));
        }
        return new RaceTcfSnapshot(nextRaceId, java.time.Instant.now(),
            RaceTcfSnapshot.Source.PROCESS_HANDICAPS, sourceRaceId, sourceRaceNumber, tcfs);
    }

    /**
     * GET /api/races/{id}/pending-handicaps — read back the locally-saved
     * adjustments for a race, or an empty list if none have been saved.
     */
    private void handleGetPendingHandicaps(HttpServletResponse resp, String raceId) throws Exception
    {
        writeJson(resp, Map.of(
            "raceId", raceId,
            "adjustments", store.pendingAdjustments(raceId)));
    }

    /**
     * GET /api/races/{id}/tcf-snapshot — return the locally-saved TCF
     * snapshot for this race, or {@code {snapshot:null}} when none exists.
     * The race page overlays snapshot TCFs on top of SailSys's current ones
     * and shows a Push / Reset banner when this is the earliest unprocessed
     * race in the series and the two diverge.
     */
    private void handleGetRaceTcfSnapshot(HttpServletResponse resp, String raceId) throws Exception
    {
        RaceTcfSnapshot snap = store.raceTcfs(raceId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("raceId", raceId);
        out.put("snapshot", snap);
        writeJson(resp, out);
    }

    /**
     * POST /api/races/{id}/save-tcfs — admin-only. Body
     * {@code {updates:[{boatId,value,spinnakerType}], seriesId}}.
     * Writes a {@code MANUAL_EDIT} snapshot locally. The push to SailSys
     * is NOT automatic — the next page load surfaces the mismatch banner
     * (Save / Reset) so the admin can confirm before publishing.
     */
    private void handleSaveRaceTcfs(HttpServletRequest req, HttpServletResponse resp, int raceId)
        throws Exception
    {
        SailSysSession session = currentSession(req);
        if (session == null)
        {
            resp.setStatus(401);
            writeJson(resp, Map.of("error", "not signed in"));
            return;
        }
        if (adminLevelForClub(session.user(), config.sailsys().clubId()) != 0)
        {
            resp.setStatus(403);
            writeJson(resp, Map.of("error", "admin required"));
            return;
        }
        JsonNode body = MAPPER.readTree(req.getInputStream());
        JsonNode updates = body.path("updates");
        if (!updates.isArray() || updates.isEmpty())
        {
            resp.setStatus(400);
            writeJson(resp, Map.of("error", "updates array required"));
            return;
        }

        // Build the snapshot from the request body (each row already carries
        // boatId/value/spinnakerType — the client gathered them from the
        // entrants payload).
        List<RaceTcfSnapshot.TcfEntry> tcfs = new ArrayList<>();
        for (JsonNode u : updates)
        {
            tcfs.add(new RaceTcfSnapshot.TcfEntry(
                u.path("boatId").asText(),
                u.path("value").asDouble(),
                u.path("spinnakerType").isMissingNode() || u.path("spinnakerType").isNull()
                    ? 1 : u.path("spinnakerType").asInt()));
        }
        RaceTcfSnapshot snap = new RaceTcfSnapshot(String.valueOf(raceId),
            java.time.Instant.now(), RaceTcfSnapshot.Source.MANUAL_EDIT, null, null, tcfs);
        store.putRaceTcfs(String.valueOf(raceId), snap);

        writeJson(resp, Map.of(
            "ok", true,
            "raceId", raceId,
            "saved", tcfs.size()));
    }

    /**
     * POST /api/races/{id}/push-handicaps — admin-only. Reads the local
     * snapshot for the race and pushes it to SailSys via the bulk handicaps
     * endpoint. Triggered by the "Push to SailSys" button on the mismatch
     * banner that surfaces when the snapshot was written by Save Handicaps
     * on the previous race.
     */
    private void handlePushRaceHandicaps(HttpServletRequest req, HttpServletResponse resp, int raceId)
        throws Exception
    {
        SailSysSession session = currentSession(req);
        if (session == null)
        {
            resp.setStatus(401);
            writeJson(resp, Map.of("error", "not signed in"));
            return;
        }
        if (adminLevelForClub(session.user(), config.sailsys().clubId()) != 0)
        {
            resp.setStatus(403);
            writeJson(resp, Map.of("error", "admin required"));
            return;
        }
        RaceTcfSnapshot snap = store.raceTcfs(String.valueOf(raceId));
        if (snap == null || snap.tcfs() == null || snap.tcfs().isEmpty())
        {
            resp.setStatus(400);
            writeJson(resp, Map.of("error", "no local TCF snapshot to push"));
            return;
        }
        pushSnapshotToSailSys(session.token(), raceId, snap);
        writeJson(resp, Map.of(
            "ok", true,
            "raceId", raceId,
            "pushed", snap.tcfs().size()));
    }

    /**
     * DELETE /api/races/{id}/tcf-snapshot — admin-only. Drops the local
     * snapshot so the race page falls back to SailSys's TCFs (the "Reset"
     * action on the mismatch banner). No SailSys traffic.
     */
    private void handleDeleteRaceTcfSnapshot(HttpServletRequest req, HttpServletResponse resp,
                                             String raceId) throws Exception
    {
        SailSysSession session = currentSession(req);
        if (session == null)
        {
            resp.setStatus(401);
            writeJson(resp, Map.of("error", "not signed in"));
            return;
        }
        if (adminLevelForClub(session.user(), config.sailsys().clubId()) != 0)
        {
            resp.setStatus(403);
            writeJson(resp, Map.of("error", "admin required"));
            return;
        }
        boolean removed = store.deleteRaceTcfs(raceId);
        writeJson(resp, Map.of("ok", true, "raceId", raceId, "removed", removed));
    }

    /**
     * POST /api/races/{id}/tcf-snapshot — admin-only. Write a snapshot
     * directly (e.g. manual JSON upload). Body is the {@link RaceTcfSnapshot}
     * shape; {@code savedAt} is stamped server-side when null.
     */
    private void handleSaveRaceTcfSnapshot(HttpServletRequest req, HttpServletResponse resp,
                                           String raceId) throws Exception
    {
        SailSysSession session = currentSession(req);
        if (session == null)
        {
            resp.setStatus(401);
            writeJson(resp, Map.of("error", "not signed in"));
            return;
        }
        if (adminLevelForClub(session.user(), config.sailsys().clubId()) != 0)
        {
            resp.setStatus(403);
            writeJson(resp, Map.of("error", "admin required"));
            return;
        }
        RaceTcfSnapshot incoming = MAPPER.readValue(req.getInputStream(), RaceTcfSnapshot.class);
        RaceTcfSnapshot snap = new RaceTcfSnapshot(
            raceId,
            incoming.savedAt() != null ? incoming.savedAt() : java.time.Instant.now(),
            incoming.source() != null ? incoming.source() : RaceTcfSnapshot.Source.MANUAL_EDIT,
            incoming.sourceRaceId(),
            incoming.sourceRaceNumber(),
            incoming.tcfs() != null ? incoming.tcfs() : List.of());
        store.putRaceTcfs(raceId, snap);
        writeJson(resp, Map.of("ok", true, "raceId", raceId, "saved", snap.tcfs().size()));
    }

    /**
     * Fetches the current entrants from SailSys, overlays the snapshot's
     * TCF values onto each entrant's {@code handicap.currentHandicaps[]}
     * row whose {@code definition.id} matches the configured handicap, then
     * PUTs the mutated array back via {@link SailSysClient#updateRaceHandicaps}.
     * Boats present in entrants but missing from the snapshot keep their
     * existing TCFs.
     */
    private void pushSnapshotToSailSys(String token, int raceId, RaceTcfSnapshot snap) throws Exception
    {
        JsonNode entrants = sailsys.fetchRaceEntrants(token, raceId);
        if (!entrants.isArray())
            throw new IllegalStateException("SailSys returned non-array entrants for race " + raceId);
        Map<String, Double> byBoat = new HashMap<>();
        for (RaceTcfSnapshot.TcfEntry t : snap.tcfs())
            byBoat.put(t.boatId(), t.value());
        int defId = config.sailsys().handicapDefinitionId();
        for (JsonNode e : entrants)
        {
            String boatId = e.path("boat").path("id").asText(null);
            if (boatId == null) continue;
            Double v = byBoat.get(boatId);
            if (v == null) continue;
            JsonNode list = e.path("handicap").path("currentHandicaps");
            if (!list.isArray()) continue;
            for (JsonNode h : list)
            {
                if (h.path("definition").path("id").asInt() == defId && h instanceof ObjectNode obj)
                {
                    obj.put("value", v.doubleValue());
                    break;
                }
            }
            // currentHandicapForSort mirrors one of the currentHandicaps entries;
            // SailSys echoes it but the value comparison happens on currentHandicaps.
            JsonNode sort = e.path("currentHandicapForSort");
            if (sort.path("definition").path("id").asInt() == defId && sort instanceof ObjectNode sortObj)
                sortObj.put("value", v.doubleValue());
        }
        sailsys.updateRaceHandicaps(token, raceId, entrants);
    }

    /**
     * GET /api/calibration — return the saved SailSys calibration ({@code null}
     * when never probed). The race page uses the presence of a calibration to
     * decide whether to enable the Process Handicaps button.
     */
    private void handleGetCalibration(HttpServletResponse resp) throws Exception
    {
        org.mortbay.sailing.jinx.model.Calibration cal = store.calibration();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("calibration", cal);
        writeJson(resp, out);
    }

    /**
     * POST /api/calibration — persist a calibration computed from a probe
     * response. Admin-only. Body shape:
     * <pre>{@code
     *   {
     *     "v0Knots": 6.107,
     *     "courseLengthNm": 100.0,
     *     "slowestTcf": 0.79, "slowestStartOffsetSeconds": 0,
     *     "fastestTcf": 1.12, "fastestStartOffsetSeconds": 21960
     *   }
     * }</pre>
     * {@code computedAt} is stamped server-side.
     */
    private void handleSaveCalibration(HttpServletRequest req, HttpServletResponse resp) throws Exception
    {
        SailSysSession session = currentSession(req);
        if (session == null)
        {
            resp.setStatus(401);
            writeJson(resp, Map.of("error", "not signed in"));
            return;
        }
        if (adminLevelForClub(session.user(), config.sailsys().clubId()) != 0)
        {
            resp.setStatus(403);
            writeJson(resp, Map.of("error", "admin required"));
            return;
        }
        JsonNode body = MAPPER.readTree(req.getInputStream());
        double v0 = body.path("v0Knots").asDouble(0);
        double courseLengthNm = body.path("courseLengthNm").asDouble(0);
        double slowestTcf = body.path("slowestTcf").asDouble(0);
        long slowestOff = body.path("slowestStartOffsetSeconds").asLong(0);
        double fastestTcf = body.path("fastestTcf").asDouble(0);
        long fastestOff = body.path("fastestStartOffsetSeconds").asLong(0);
        if (v0 <= 0 || courseLengthNm <= 0 || slowestTcf <= 0 || fastestTcf <= 0)
        {
            resp.setStatus(400);
            writeJson(resp, Map.of("error",
                "v0Knots, courseLengthNm, slowestTcf and fastestTcf must all be positive"));
            return;
        }
        org.mortbay.sailing.jinx.model.Calibration cal =
            new org.mortbay.sailing.jinx.model.Calibration(
                v0, courseLengthNm, slowestTcf, slowestOff,
                fastestTcf, fastestOff, java.time.Instant.now());
        store.putCalibration(cal);
        writeJson(resp, Map.of("ok", true, "calibration", cal));
    }

    private static int adminLevelForClub(SailSysClient.User u, int clubId)
    {
        if (u == null || u.clubAdminPositions == null) return -1;
        for (SailSysClient.ClubAdminPosition pos : u.clubAdminPositions)
        {
            if (pos != null && pos.club != null && pos.club.id == clubId)
                return pos.adminLevel;
        }
        return -1;
    }

    private static Map<String, String> readJson(HttpServletRequest req) throws IOException
    {
        if (req.getContentLength() <= 0)
            return new HashMap<>();
        return MAPPER.readValue(req.getInputStream(), new TypeReference<Map<String, String>>() { });
    }

    private void todo(HttpServletResponse resp, String message) throws IOException
    {
        resp.setStatus(501);
        writeJson(resp, Map.of("error", "not implemented", "todo", message));
    }

    private static void writeJson(HttpServletResponse resp, Object body) throws IOException
    {
        MAPPER.writeValue(resp.getWriter(), body);
    }
}
