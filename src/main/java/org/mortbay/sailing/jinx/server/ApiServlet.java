package org.mortbay.sailing.jinx.server;

import java.io.IOException;
import java.util.Map;

import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.mortbay.sailing.jinx.config.JinxConfig;
import org.mortbay.sailing.jinx.pursuit.HandicapEngine;
import org.mortbay.sailing.jinx.sailsys.SailSysClient;
import org.mortbay.sailing.jinx.store.JsonStore;

/**
 * REST API consumed by the static HTML front end. Endpoint surface is the one
 * documented in {@code CLAUDE.md}.
 *
 * <p>Most handlers are TODOs. The two endpoints wired now are
 * {@code GET /api/auth/status} and {@code POST /api/auth/login} — the minimum
 * needed to confirm the SailSys session is alive from the UI.
 */
public class ApiServlet extends HttpServlet
{
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

        switch (path)
        {
            case "/auth/status" -> writeJson(resp, Map.of(
                "authenticated", sailsys.isAuthenticated(),
                "user", sailsys.currentUser() == null ? null : Map.of(
                    "email", sailsys.currentUser().email,
                    "firstname", sailsys.currentUser().firstname,
                    "surname", sailsys.currentUser().surname)));
            case "/boats" -> writeJson(resp, store.boats().values());
            case "/races" -> writeJson(resp, store.races().values());
            case "/audit" -> writeJson(resp, store.audit());
            default -> {
                if (path.matches("/races/[^/]+/startTimes"))
                    todo(resp, "GET " + path + " — compute start sheet");
                else
                    resp.sendError(404);
            }
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
                case "/auth/login" -> {
                    sailsys.login();
                    writeJson(resp, Map.of("ok", true,
                        "email", sailsys.currentUser().email));
                }
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
