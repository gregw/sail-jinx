package org.mortbay.sailing.jinx.server;

import java.io.IOException;
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
import org.mortbay.sailing.jinx.model.RaceTimes;
import org.mortbay.sailing.jinx.pursuit.HandicapEngine;
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
    private static final java.util.regex.Pattern RACE_PROCESS_RESULTS =
        java.util.regex.Pattern.compile("/races/(\\d+)/process-results");
    private static final java.util.regex.Pattern RACE_RESULTS_STATUS =
        java.util.regex.Pattern.compile("/races/(\\d+)/results-status");

    private static final JsonMapper MAPPER = JsonMapper.builder()
        .addModule(new JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        .build();

    private final JinxConfig config;
    private final JsonStore store;
    private final SailSysClient sailsys;
    private final HandicapEngine engine;

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
                case "/boats" -> writeJson(resp, store.boats().values());
                case "/races" -> writeJson(resp, store.races().values());
                case "/audit" -> writeJson(resp, store.audit());
                default -> {
                    java.util.regex.Matcher sr = SERIES_RACES.matcher(path);
                    java.util.regex.Matcher re = RACE_ENTRANTS.matcher(path);
                    java.util.regex.Matcher rs = RACE_STATUS.matcher(path);
                    java.util.regex.Matcher rt = RACE_TIMES.matcher(path);
                    if (sr.matches())
                        handleSeriesRaces(req, resp, Integer.parseInt(sr.group(1)));
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
                default -> {
                    java.util.regex.Matcher tcfs = SERIES_TCFS.matcher(path);
                    java.util.regex.Matcher rt = RACE_TIMES.matcher(path);
                    java.util.regex.Matcher ev = RACE_ENTRANTS_VIS.matcher(path);
                    java.util.regex.Matcher sv = RACE_STARTTIMES_VIS.matcher(path);
                    java.util.regex.Matcher rp = RACE_PROCESS.matcher(path);
                    if (tcfs.matches())
                        handleSaveTcfs(req, resp, Integer.parseInt(tcfs.group(1)));
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
        writeJson(resp, Map.of(
            "clubId", config.sailsys().clubId(),
            "handicapDefinitionId", config.sailsys().handicapDefinitionId(),
            "series", sailsys.fetchClubSeries(session.token(), config.sailsys().clubId())));
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

        // Bundle per-race status so the page can render the table in one paint.
        // We tolerate per-race status failures (rare, but possible if a race is
        // mid-edit on SailSys side) and report them as nulls — the UI shows
        // "—" rather than blowing up the whole list.
        Map<String, Object> statusByRaceId = new LinkedHashMap<>();
        if (races.isArray())
        {
            for (JsonNode race : races)
            {
                int raceId = race.path("id").asInt();
                if (raceId == 0) continue;
                try
                {
                    statusByRaceId.put(String.valueOf(raceId),
                        sailsys.fetchRaceStatus(session.token(), raceId));
                }
                catch (Exception e)
                {
                    LOG.warn("status fetch failed for race {}: {}", raceId, e.toString());
                    statusByRaceId.put(String.valueOf(raceId), null);
                }
            }
        }

        writeJson(resp, Map.of(
            "seriesId", seriesId,
            "races", races,
            "status", statusByRaceId));
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

        // 2. Finishers template — every boat with the full echo-back fields.
        JsonNode templateNode = sailsys.fetchRaceFinishers(token, raceId);
        if (!templateNode.isArray())
        {
            resp.setStatus(502);
            writeJson(resp, Map.of("error", "finishers fetch did not return an array"));
            return;
        }
        ArrayNode template = (ArrayNode) templateNode;

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
            if (defs != null && bs.path("flags").isArray())
            {
                for (JsonNode flag : bs.path("flags"))
                {
                    JsonNode penDef = defs.get(flag.asText());
                    if (penDef != null) penalties.add(penDef);
                    // Unknown flags (e.g. AVG) are silently dropped — no
                    // SailSys penalty exists for them.
                }
            }
            obj.set("penalties", penalties);
        }

        // 5. PUT starters — startedRace flag goes first.
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

        sailsys.checkRaceResults(token, raceId);

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
     * Set course length and trigger start-time processing. Body:
     * {@code {courseLength: number}}. Implementation:
     * <ol>
     *   <li>Fetch the race status to get the current divisionTiming.</li>
     *   <li>Patch courseLength on every division (MYC Twilight has one).</li>
     *   <li>PUT it back, which kicks off SailSys-side processing.</li>
     *   <li>Poll status until handicapAndStartTimeProcessingStatus leaves 1
     *       (= "processing"), up to a 30 s deadline.</li>
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
        if (body.path("courseLength").isMissingNode() || body.path("courseLength").isNull())
        {
            resp.setStatus(400);
            writeJson(resp, Map.of("error", "courseLength required"));
            return;
        }
        double courseLength = body.path("courseLength").asDouble();

        JsonNode status = sailsys.fetchRaceStatus(session.token(), raceId);
        JsonNode divisionTiming = status.path("divisionTiming");
        if (!divisionTiming.isArray() || divisionTiming.isEmpty())
        {
            resp.setStatus(400);
            writeJson(resp, Map.of("error", "race has no divisionTiming yet"));
            return;
        }
        // Mutate courseLength on a copy so we don't alter the cached node.
        JsonNode patched = divisionTiming.deepCopy();
        for (JsonNode dt : patched)
        {
            if (dt instanceof ObjectNode on) on.put("courseLength", courseLength);
        }

        sailsys.setRaceTiming(session.token(), raceId, patched);

        // Poll. processingStatus: 0 = pending, 1 = processing, 2 = processed.
        // We see the transition 0→1→2; if SailSys is fast it can be 0→2 in one
        // tick. Either way, the moment we see anything other than 1 we're done.
        long deadline = System.currentTimeMillis() + 30_000L;
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
        out.put("courseLength", courseLength);
        out.put("status", finalStatus);
        if (timedOut) out.put("error", "Processing still in progress after 30s — try refreshing in a moment.");
        writeJson(resp, out);
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
            incoming.dutyBoatId(), incoming.times());
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
        JinxConfig.Algorithm a = config.algorithm();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sailsys", Map.of(
            "clubId", s.clubId(),
            "handicapDefinitionId", s.handicapDefinitionId(),
            "timezone", s.timezone()));
        out.put("algorithm", Map.of(
            "penaltyList", a.penaltyList(),
            "idealRaceLength", a.idealRaceLength(),
            "dnfAllowance", a.dnfAllowance(),
            "earliestStart", a.earliestStart()));
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

    private static Map<String, Object> userSummary(SailSysSession session)
    {
        SailSysClient.User u = session.user();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("email", u.email);
        out.put("firstname", u.firstname);
        out.put("surname", u.surname);
        return out;
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
