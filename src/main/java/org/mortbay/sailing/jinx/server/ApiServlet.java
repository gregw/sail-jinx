package org.mortbay.sailing.jinx.server;

import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.mortbay.sailing.jinx.config.JinxConfig;
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
    private static final java.util.regex.Pattern RACE_ENTRANTS =
        java.util.regex.Pattern.compile("/races/(\\d+)/entrants");
    private static final java.util.regex.Pattern RACE_STATUS =
        java.util.regex.Pattern.compile("/races/(\\d+)/status");

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
                    if (sr.matches())
                        handleSeriesRaces(req, resp, Integer.parseInt(sr.group(1)));
                    else if (re.matches())
                        handleRaceEntrants(req, resp, Integer.parseInt(re.group(1)));
                    else if (rs.matches())
                        handleRaceStatus(req, resp, Integer.parseInt(rs.group(1)));
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
                    if (path.matches("/races/[^/]+/results"))
                        todo(resp, "POST " + path + " — save results");
                    else if (path.matches("/races/[^/]+/process"))
                        todo(resp, "POST " + path + " — run TCF update");
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
