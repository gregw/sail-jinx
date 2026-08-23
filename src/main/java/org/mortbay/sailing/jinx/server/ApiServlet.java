package org.mortbay.sailing.jinx.server;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.mortbay.sailing.jinx.config.AuthConfig;
import org.mortbay.sailing.jinx.config.JinxConfig;
import org.mortbay.sailing.jinx.identity.BoatRegistry;
import org.mortbay.sailing.jinx.identity.FleetJson;
import org.mortbay.sailing.jinx.identity.IdGenerator;
import org.mortbay.sailing.jinx.model.Adjustment;
import org.mortbay.sailing.jinx.model.AuditEntry;
import org.mortbay.sailing.jinx.model.Boat;
import org.mortbay.sailing.jinx.model.Design;
import org.mortbay.sailing.jinx.model.Entrant;
import org.mortbay.sailing.jinx.model.FinishStatus;
import org.mortbay.sailing.jinx.model.Race;
import org.mortbay.sailing.jinx.model.RaceEntrants;
import org.mortbay.sailing.jinx.model.RaceTimes;
import org.mortbay.sailing.jinx.model.Result;
import org.mortbay.sailing.jinx.model.Roster;
import org.mortbay.sailing.jinx.model.Series;
import org.mortbay.sailing.jinx.model.Spinnaker;
import org.mortbay.sailing.jinx.model.StartSheet;
import org.mortbay.sailing.jinx.model.StartTime;
import org.mortbay.sailing.jinx.pursuit.Competitor;
import org.mortbay.sailing.jinx.pursuit.HandicapEngine;
import org.mortbay.sailing.jinx.pursuit.PursuitHandicapEngine;
import org.mortbay.sailing.jinx.pursuit.SolarTimes;
import org.mortbay.sailing.jinx.store.JsonStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * REST API consumed by the static HTML front end. Every endpoint reads and
 * writes the local {@link JsonStore} — sail-jinx v2 talks to nothing else.
 *
 * <h2>Endpoints</h2>
 * <pre>
 *   GET    /api/config                        server + algorithm defaults, store health
 *   GET    /api/boats                         the fleet register
 *   GET    /api/designs                       hull types, learned from boat entry
 *   POST   /api/boats                         create or update a boat
 *   POST   /api/boats/import                  load the fleet from a sailing-pf export
 *   GET    /api/series                        all series
 *   POST   /api/series                        create or update a series
 *   GET    /api/series/{id}/config            per-series algorithm settings
 *   POST   /api/series/{id}/config            save them
 *   GET    /api/series/{id}/roster            boats entered for the series
 *   POST   /api/series/{id}/roster            save the roster
 *   GET    /api/races                         all races
 *   POST   /api/races                         create or update a race
 *   GET    /api/races/{id}                    everything the race page needs, in one call
 *   POST   /api/races/{id}/entrants           replace the entrant list
 *   POST   /api/races/{id}/entrants/seed      seed entrants from the roster or the previous race
 *   POST   /api/races/{id}/entrants/import    add entrants from a sailing-pf export
 *   POST   /api/races/{id}/start-times        compute and publish the pursuit start sheet
 *   GET    /api/races/{id}/times              RO-captured came / start / finish
 *   POST   /api/races/{id}/times              save them
 *   POST   /api/races/{id}/process-handicaps  run the Jinx algorithm (computes, saves nothing)
 *   POST   /api/races/{id}/save-handicaps     save adjustments, carry TCFs to the next race
 *   DELETE /api/races/{id}/adjustments        unlock the race for reprocessing
 *   GET    /api/audit                         the audit log
 * </pre>
 *
 * <h2>Authorisation</h2>
 * Three tiers, and the first one is the point: <b>every GET above answers anybody</b>.
 * A club publishes its results, and a season that can only be read by people with
 * accounts is a season nobody reads.
 *
 * <pre>
 *   VIEWER        no account          every GET
 *   RACE_OFFICER  a club account      + running a race night: times, the start
 *                                       sheet, handicaps, unlock, and editing an
 *                                       entrant's TCF, division or casual flag
 *   ADMIN         listed in admins:   + what a race night runs on: series, races,
 *                                       roster, series config, the fleet register,
 *                                       and which boats are in a race at all
 * </pre>
 *
 * <p>The split between the last two is <em>composing versus running</em>. Deciding that
 * there is a race, who is in the series, and which boats are scored in tonight's race is
 * the admin's. Recording what those boats then did — including processing the handicaps,
 * which is what running a race means — is the race officer's.
 *
 * <p>That line cuts through one endpoint rather than between two.
 * {@code POST /api/races/{id}/entrants} sends the whole list, so that add, remove and
 * edit cannot be separated on the way in; the role is therefore decided from the diff.
 * Same set of boats, different values: race officer. A boat more or fewer: admin. See
 * {@link #changesTheFleet}.
 *
 * <p>With {@code auth.yaml} absent or off, everyone is an ADMIN — one machine on one
 * desk, which is what this was before it was hosted. See {@link #denyUnless}.
 */
public class ApiServlet extends HttpServlet
{
    private static final Logger LOG = LoggerFactory.getLogger(ApiServlet.class);

    // Series ids are club-scoped and contain a slash (myc.org.au/2026-winter-twilight),
    // so the id group has to span one. Each pattern is anchored by a fixed suffix and
    // matched whole, so there is no ambiguity about where the id ends.
    private static final Pattern SERIES_CONFIG = Pattern.compile("/series/(.+)/config");
    private static final Pattern SERIES_ROSTER = Pattern.compile("/series/(.+)/roster");
    private static final Pattern SERIES_RACES = Pattern.compile("/series/(.+)/races");
    private static final Pattern RACE = Pattern.compile("/races/([^/]+)");
    private static final Pattern RACE_ENTRANTS = Pattern.compile("/races/([^/]+)/entrants");
    private static final Pattern RACE_ENTRANTS_SEED = Pattern.compile("/races/([^/]+)/entrants/seed");
    private static final Pattern RACE_ENTRANTS_IMPORT = Pattern.compile("/races/([^/]+)/entrants/import");
    private static final Pattern RACE_START_TIMES = Pattern.compile("/races/([^/]+)/start-times");
    private static final Pattern RACE_TIMES = Pattern.compile("/races/([^/]+)/times");
    private static final Pattern RACE_PROCESS_HANDICAPS = Pattern.compile("/races/([^/]+)/process-handicaps");
    private static final Pattern RACE_SAVE_HANDICAPS = Pattern.compile("/races/([^/]+)/save-handicaps");
    private static final Pattern RACE_ADJUSTMENTS = Pattern.compile("/races/([^/]+)/adjustments");

    /** A season is twenty-odd races; a request for hundreds is a typo, not a season. */
    private static final int MAX_RACES_PER_REQUEST = 100;

    private static final JsonMapper MAPPER = JsonMapper.builder()
        .addModule(new JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        .build();

    private final JinxConfig config;
    private final AuthConfig auth;
    private final JsonStore store;
    private final HandicapEngine engine;
    private final BoatRegistry registry;
    private final String version;

    public ApiServlet(JinxConfig config, AuthConfig auth, JsonStore store,
                      HandicapEngine engine, BoatRegistry registry, String version)
    {
        this.config = config;
        this.auth = auth;
        this.store = store;
        this.engine = engine;
        this.registry = registry;
        this.version = version;
    }

    /** Authentication off, for tests and for the single-machine deployment. */
    public ApiServlet(JinxConfig config, JsonStore store, HandicapEngine engine,
                      BoatRegistry registry, String version)
    {
        this(config, AuthConfig.disabled(), store, engine, registry, version);
    }

    /**
     * Who is asking.
     *
     * <p>With {@code auth.yaml} absent or off this is always {@link Role#ADMIN}, which is
     * what sail-jinx did before it had a login: one machine on one desk is the security
     * boundary.
     *
     * <p>With authentication on there are three answers, and the first of them is the one
     * that shapes the app: <b>nobody signed in is a {@link Role#VIEWER}, not a refusal</b>.
     * A club's results are published to be read, so every page and every GET answers a
     * stranger. A club account is a {@link Role#RACE_OFFICER} and can run a race night
     * end to end. An account named in {@code admins} is an {@link Role#ADMIN} and owns
     * what a race night runs on — the series, the races, the roster.
     */
    Role currentRole(HttpServletRequest req)
    {
        if (auth == null || !auth.enabled())
            return Role.ADMIN;
        SignedIn who = SignedIn.of(req, auth);
        if (who.admin())
            return Role.ADMIN;
        return who.isSignedIn() ? Role.RACE_OFFICER : Role.VIEWER;
    }

    /**
     * What a caller is allowed to do, in increasing order — {@link #atLeast} depends on
     * the order, so a new tier goes in its rightful place rather than on the end.
     */
    public enum Role
    {
        /** Anyone at all. Can read every page and change nothing. */
        VIEWER,
        /** A club account. Runs race night: entrants, times, start sheet, handicaps. */
        RACE_OFFICER,
        /** A club account named in {@code admins}. Owns the shape of the season. */
        ADMIN;

        public boolean atLeast(Role required)
        {
            return ordinal() >= required.ordinal();
        }
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException
    {
        String path = path(req);
        resp.setContentType("application/json");
        try
        {
            switch (path)
            {
                case "/config" -> writeJson(resp, publicConfig());
                case "/whoami" -> writeJson(resp, whoami(req));
                case "/boats" -> writeJson(resp, sortedBoats());
                case "/designs" -> writeJson(resp, sortedDesigns());
                case "/series" -> writeJson(resp, sortedSeries());
                case "/races" -> writeJson(resp, allRaces());
                case "/audit" -> writeJson(resp, store.audit());
                default -> doGetPath(req, path, resp);
            }
        }
        catch (Exception e)
        {
            fail(resp, e);
        }
    }

    private void doGetPath(HttpServletRequest req, String path,
        HttpServletResponse resp) throws Exception
    {
        Matcher m;
        if ((m = SERIES_CONFIG.matcher(path)).matches())
            writeJson(resp, seriesConfigBody(m.group(1)));
        else if ((m = SERIES_ROSTER.matcher(path)).matches())
            writeJson(resp, rosterBody(m.group(1)));
        else if ((m = SERIES_RACES.matcher(path)).matches())
            writeJson(resp, store.racesInSeries(m.group(1)));
        else if ((m = RACE_TIMES.matcher(path)).matches())
            writeJson(resp, mapOf("raceId", m.group(1), "times", store.raceTimes(m.group(1))));
        else if ((m = RACE.matcher(path)).matches())
            writeRaceBundle(req, resp, m.group(1));
        else
            resp.sendError(404);
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException
    {
        String path = path(req);
        resp.setContentType("application/json");
        try
        {
            switch (path)
            {
                case "/boats" -> handleSaveBoat(req, resp);
                case "/boats/import" -> handleImportBoats(req, resp);
                case "/series" -> handleSaveSeries(req, resp);
                case "/races" -> handleSaveRace(req, resp);
                default -> doPostPath(path, req, resp);
            }
        }
        catch (Exception e)
        {
            fail(resp, e);
        }
    }

    private void doPostPath(String path, HttpServletRequest req, HttpServletResponse resp)
        throws Exception
    {
        Matcher m;
        // Longest patterns first: /entrants/seed would otherwise be shadowed.
        if ((m = RACE_ENTRANTS_SEED.matcher(path)).matches())
            handleSeedEntrants(req, resp, m.group(1));
        else if ((m = RACE_ENTRANTS_IMPORT.matcher(path)).matches())
            handleImportEntrants(req, resp, m.group(1));
        else if ((m = RACE_ENTRANTS.matcher(path)).matches())
            handleSaveEntrants(req, resp, m.group(1));
        else if ((m = RACE_START_TIMES.matcher(path)).matches())
            handleComputeStartTimes(req, resp, m.group(1));
        else if ((m = RACE_TIMES.matcher(path)).matches())
            handleSaveRaceTimes(req, resp, m.group(1));
        else if ((m = RACE_PROCESS_HANDICAPS.matcher(path)).matches())
            handleProcessHandicaps(req, resp, m.group(1));
        else if ((m = RACE_SAVE_HANDICAPS.matcher(path)).matches())
            handleSaveHandicaps(req, resp, m.group(1));
        else if ((m = SERIES_CONFIG.matcher(path)).matches())
            handleSaveSeriesConfig(req, resp, m.group(1));
        else if ((m = SERIES_ROSTER.matcher(path)).matches())
            handleSaveRoster(req, resp, m.group(1));
        else
            resp.sendError(404);
    }

    @Override
    protected void doDelete(HttpServletRequest req, HttpServletResponse resp) throws IOException
    {
        String path = path(req);
        resp.setContentType("application/json");
        try
        {
            Matcher m = RACE_ADJUSTMENTS.matcher(path);
            if (m.matches())
                handleUnlockRace(req, resp, m.group(1));
            else
                resp.sendError(404);
        }
        catch (Exception e)
        {
            fail(resp, e);
        }
    }

    // --- Config --------------------------------------------------------------

    private Map<String, Object> publicConfig()
    {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("version", version);
        out.put("club", mapOf(
            "domain", config.club().domain(),
            "shortName", config.club().shortName(),
            "longName", config.club().longName(),
            "timezone", config.club().timezone()));
        out.put("algorithm", algorithmMap(config.algorithm()));
        // Surfaced so a corrupt store file is visible in the UI instead of
        // quietly presenting as missing data.
        out.put("storeErrors", store.loadErrors());
        return out;
    }

    // --- Fleet register ------------------------------------------------------

    private List<Boat> sortedBoats()
    {
        return store.boats().values().stream()
            .sorted(Comparator.comparing(Boat::sailNumber, String.CASE_INSENSITIVE_ORDER))
            .toList();
    }

    /**
     * Create or update a register boat.
     *
     * <p>A body with an {@code id} is an edit of that exact record. A body without one is
     * an <em>entry</em>, and goes through {@link BoatRegistry} so it lands on the boat it
     * means: the same hull typed twice, with a sponsor prefix, or with the design filled
     * in the second time, must not become two records.
     *
     * <p>There is no delete — retiring a boat is {@code active: false}, because races it
     * has already sailed still name it.
     */
    private void handleSaveBoat(HttpServletRequest req, HttpServletResponse resp) throws Exception
    {
        if (denyUnless(req, resp, Role.ADMIN))
            return;
        JsonNode body = MAPPER.readTree(req.getInputStream());
        String sailNumber = text(body, "sailNumber");
        String name = text(body, "name");
        if (isBlank(sailNumber) && isBlank(name))
        {
            badRequest(resp, "a boat needs at least a sail number or a name");
            return;
        }

        String id = text(body, "id");
        if (isBlank(id))
        {
            BoatRegistry.Resolution r = registry.findOrCreate(rawBoatOf(body), null);
            if (!r.resolved())
            {
                resp.setStatus(409);
                writeJson(resp, mapOf("error", r.note(), "outcome", r.outcome()));
                return;
            }
            writeJson(resp, mapOf("ok", true, "outcome", r.outcome(),
                "note", r.note(), "boat", r.boat()));
            return;
        }

        // Editing an existing record: the caller owns the id, so keep it. Renaming a boat
        // through this path deliberately does not move the id — an id that changed under
        // an edit would break every reference the user could not see.
        Boat existing = store.boats().get(id);
        Boat boat = new Boat(
            id,
            sailNumber == null ? "" : sailNumber.trim(),
            name == null ? "" : name.trim(),
            existing == null ? text(body, "designId") : existing.designId(),
            body.path("casual").asBoolean(false),
            !body.has("active") || body.path("active").asBoolean(true),
            text(body, "notes"));
        store.putBoat(boat);
        writeJson(resp, mapOf("ok", true, "outcome", "EDITED", "boat", boat));
    }

    /** Read a raw boat entry from a JSON body, exactly as typed. */
    private static BoatRegistry.RawBoat rawBoatOf(JsonNode body)
    {
        return new BoatRegistry.RawBoat(
            text(body, "sailNumber"),
            text(body, "name"),
            text(body, "design"),
            text(body, "notes"),
            body.path("casual").asBoolean(false));
    }

    /** Learned designs, for labelling the register. */
    private List<Design> sortedDesigns()
    {
        return store.designs().values().stream()
            .sorted(Comparator.comparing(Design::displayName, String.CASE_INSENSITIVE_ORDER))
            .toList();
    }

    /**
     * Bulk-load the fleet from CSV. Body is the file's text, either as {@code text/csv} or
     * as {@code {"csv": "..."}}.
     *
     * <p>Every row goes through {@link BoatRegistry}, so importing the same list twice
     * matches rather than duplicating, and a list that gained a design column upgrades the
     * boats already registered without it.
     *
     * <p>A fleet list usually carries TCF, division and spinnaker as well. Those are terms
     * of a <em>series entry</em>, not facts about a boat, so they have nowhere to go
     * unless a series is named: pass {@code ?seriesId=...} and they are applied to that
     * series' roster. Without one the boats are still registered and the entry columns are
     * reported as not applied, rather than being silently written onto the register.
     *
     * <p>Returns a per-row report rather than a count. A bulk import of a hand-maintained
     * spreadsheet always has surprises in it, and the useful answer is which rows they
     * were — the ones that conflicted, the ones matched under another name, and the
     * columns that were not understood.
     */
    /**
     * Load the fleet from a sailing-pf export (see {@link FleetJson}). Body is the file's
     * text, either as {@code application/json} or wrapped as {@code {"json": "..."}}.
     *
     * <p><b>Handicap and variant are ignored here.</b> Neither is a property of a boat —
     * a handicap belongs to a series entry, and so does a spinnaker choice — so this
     * endpoint takes identity only. The race-entrants import is where those land.
     *
     * <p>Every row goes through {@link BoatRegistry}, so importing the same file twice
     * matches rather than duplicating, a boat held without a design is upgraded from the
     * design carried in the export's id, and a sail number or name that has since changed
     * resolves through {@code aliases.yaml}.
     *
     * <p>Returns a per-row report rather than a count: a fleet export always has a
     * surprise in it, and the useful answer is which rows they were.
     */
    private void handleImportBoats(HttpServletRequest req, HttpServletResponse resp)
        throws Exception
    {
        if (denyUnless(req, resp, Role.ADMIN))
            return;
        FleetJson.Parsed parsed = FleetJson.parse(importBody(req));
        boolean dryRun = "true".equalsIgnoreCase(req.getParameter("dryRun"));

        List<Map<String, Object>> results = new ArrayList<>();
        Map<String, Integer> tally = new LinkedHashMap<>();
        for (FleetJson.Row row : parsed.rows())
        {
            Map<String, Object> out = rowHeader(row);
            if (dryRun)
            {
                Boat known = resolveExisting(row);
                out.put("outcome", "PREVIEW");
                out.put("boatId", known == null ? null : known.id());
            }
            else
            {
                applyRow(row, out, tally);
            }
            results.add(out);
        }

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("ok", parsed.problems().isEmpty());
        report.put("dryRun", dryRun);
        report.put("rows", results);
        report.put("tally", tally);
        report.put("problems", parsed.problems());
        writeJson(resp, report);
    }

    /**
     * Add entrants to a race from the same sailing-pf export, taking each boat's handicap
     * as its TCF for this race and its variant as its spinnaker.
     *
     * <p>This is the import that carries the numbers. The export is a snapshot of what the
     * fleet is rated at, which is exactly what a race entry needs; boats not yet in the
     * register are added to it as a side effect, through the same matching.
     *
     * <p>Merges rather than replaces. A boat already entered has its TCF and spinnaker
     * updated and keeps its place; boats absent from the file are left alone, because a
     * handicap export is not a statement about who is racing tonight.
     */
    private void handleImportEntrants(HttpServletRequest req, HttpServletResponse resp,
                                      String raceId) throws Exception
    {
        if (denyUnless(req, resp, Role.ADMIN))
            return;
        Race race = store.races().get(raceId);
        if (race == null)
        {
            resp.setStatus(404);
            writeJson(resp, mapOf("error", "no such race: " + raceId));
            return;
        }
        if (rejectIfLocked(resp, raceId))
            return;

        FleetJson.Parsed parsed = FleetJson.parse(importBody(req));
        boolean dryRun = "true".equalsIgnoreCase(req.getParameter("dryRun"));

        RaceEntrants existing = store.entrants(raceId);
        Map<String, Entrant> byBoat = new LinkedHashMap<>();
        List<Entrant> oneOffs = new ArrayList<>();
        if (existing != null)
        {
            for (Entrant e : existing.entrants())
            {
                if (e.boatId() == null)
                    oneOffs.add(e);
                else
                    byBoat.put(e.boatId(), e);
            }
        }

        List<Map<String, Object>> results = new ArrayList<>();
        Map<String, Integer> tally = new LinkedHashMap<>();
        for (FleetJson.Row row : parsed.rows())
        {
            Map<String, Object> out = rowHeader(row);
            out.put("tcf", row.handicap());
            if (dryRun)
            {
                out.put("outcome", "PREVIEW");
                results.add(out);
                continue;
            }

            Boat boat = applyRow(row, out, tally);
            if (boat == null)
            {
                results.add(out);
                continue;
            }

            Entrant already = byBoat.get(boat.id());
            double tcf = row.handicap() != null ? row.handicap()
                : (already != null ? already.tcf() : 1.0);
            Spinnaker spinnaker = row.spinnaker() != null ? row.spinnaker()
                : (already != null ? already.spinnaker()
                    : defaultSpinnakerFor(race.seriesId(), boat));

            byBoat.put(boat.id(), Entrant.fromBoat(boat, tcf,
                already == null ? null : already.division(), spinnaker,
                already != null ? already.entryType()
                    : (boat.casual() ? Entrant.EntryType.CASUAL : Entrant.EntryType.ROSTER)));
            out.put("entered", already == null ? "added" : "updated");
            results.add(out);
        }

        if (!dryRun)
        {
            List<Entrant> merged = new ArrayList<>(byBoat.values());
            merged.addAll(oneOffs);
            store.putEntrants(new RaceEntrants(raceId, Instant.now(),
                RaceEntrants.TcfSource.MANUAL_EDIT,
                existing == null ? null : existing.sourceRaceId(),
                existing == null ? null : existing.sourceRaceNumber(),
                merged));
        }

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("ok", parsed.problems().isEmpty());
        report.put("dryRun", dryRun);
        report.put("raceId", raceId);
        report.put("rows", results);
        report.put("tally", tally);
        report.put("entrants", byBoat.size() + oneOffs.size());
        report.put("problems", parsed.problems());
        writeJson(resp, report);
    }

    /** The request body, whether posted raw or wrapped as a JSON object with a json field. */
    private static String importBody(HttpServletRequest req) throws IOException
    {
        String body = new String(req.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (body.stripLeading().startsWith("{"))
        {
            JsonNode node = MAPPER.readTree(body);
            if (node.hasNonNull("json"))
                return node.path("json").asText();
        }
        return body;
    }

    private static Map<String, Object> rowHeader(FleetJson.Row row)
    {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("index", row.index());
        out.put("sourceBoatId", row.sourceBoatId());
        out.put("sailNumber", row.boat().sailNumber());
        out.put("name", row.boat().name());
        return out;
    }

    /**
     * The register boat this row already names, matched on the source id alone.
     *
     * <p>sailing-pf mints ids with the same rules, so an exact hit is the strongest
     * evidence available — stronger than sail number and name, both of which change over
     * a boat's life. Used only to recognise; creating and upgrading go through the
     * registry.
     */
    private Boat resolveExisting(FleetJson.Row row)
    {
        return row.sourceBoatId() == null ? null : store.boats().get(row.sourceBoatId());
    }

    /**
     * Resolve one imported row to a register boat, recording what happened. Null only
     * when the row could not be resolved at all — a design conflict.
     */
    private Boat applyRow(FleetJson.Row row, Map<String, Object> out, Map<String, Integer> tally)
        throws IOException
    {
        Boat known = resolveExisting(row);
        if (known != null)
        {
            out.put("outcome", "MATCHED");
            out.put("note", "matched on id");
            out.put("boatId", known.id());
            tally.merge("MATCHED", 1, Integer::sum);
            return known;
        }

        BoatRegistry.Resolution r = registry.findOrCreate(row.boat(), null);
        out.put("outcome", r.outcome().name());
        if (r.note() != null)
            out.put("note", r.note());
        tally.merge(r.outcome().name(), 1, Integer::sum);
        if (r.resolved())
        {
            out.put("boatId", r.boat().id());
            out.put("designId", r.boat().designId());
        }
        return r.boat();
    }


    /**
     * Build the roster entry for an imported boat, keeping anything the list did not
     * supply. A fleet list with no spinnaker column should not quietly reset a boat that
     * was already entered as non-spinnaker.
     */
    // --- Series --------------------------------------------------------------

    private List<Series> sortedSeries()
    {
        return store.series().values().stream()
            .sorted(Comparator.comparing(Series::name, String.CASE_INSENSITIVE_ORDER))
            .toList();
    }

    private void handleSaveSeries(HttpServletRequest req, HttpServletResponse resp) throws Exception
    {
        if (denyUnless(req, resp, Role.ADMIN))
            return;
        JsonNode body = MAPPER.readTree(req.getInputStream());
        String name = text(body, "name");
        if (isBlank(name))
        {
            badRequest(resp, "name is required");
            return;
        }
        // Club-scoped, readable, and stable: myc.org.au/2026-winter-twilight. An edit
        // keeps the id it was given, so renaming a series never orphans its races.
        String id = text(body, "id");
        if (isBlank(id))
            id = IdGenerator.generateSeriesId(config.club().domain(), name);

        Series.RaceFormat format = enumOf(Series.RaceFormat.class,
            text(body, "raceFormat"), Series.RaceFormat.PURSUIT);
        Series.HandicapAlgorithm algorithm = enumOf(Series.HandicapAlgorithm.class,
            text(body, "handicapAlgorithm"), Series.HandicapAlgorithm.JINX);
        if (!format.isSupported())
        {
            badRequest(resp, format + " races are not implemented yet");
            return;
        }
        if (!algorithm.isSupported())
        {
            badRequest(resp, "the " + algorithm + " handicap is not implemented yet");
            return;
        }

        Series s = new Series(id, name.trim(),
            enumOf(Series.SpinnakerPolicy.class, text(body, "spinnakerPolicy"),
                Series.SpinnakerPolicy.MIXED),
            format, algorithm,
            body.path("archived").asBoolean(false));
        store.putSeries(s);
        writeJson(resp, mapOf("ok", true, "series", s));
    }

    /**
     * Per-series algorithm settings, falling back to the yaml defaults. The
     * response also carries the defaults so the Configure form can offer
     * "restore defaults" without a second round trip.
     */
    private Map<String, Object> seriesConfigBody(String seriesId)
    {
        JinxConfig.Algorithm saved = store.seriesConfig(seriesId);
        JinxConfig.Algorithm defaults = config.algorithm();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("seriesId", seriesId);
        out.put("isCustom", saved != null);
        out.put("config", algorithmMap(saved != null ? saved : defaults));
        out.put("defaults", algorithmMap(defaults));
        return out;
    }

    private void handleSaveSeriesConfig(HttpServletRequest req, HttpServletResponse resp,
                                        String seriesId) throws Exception
    {
        if (denyUnless(req, resp, Role.ADMIN))
            return;
        // The compact constructor fills in a safe default for every missing or
        // invalid field, so a partial payload is fine.
        JinxConfig.Algorithm posted =
            MAPPER.readValue(req.getInputStream(), JinxConfig.Algorithm.class);
        store.putSeriesConfig(seriesId, posted);
        writeJson(resp, mapOf("ok", true, "seriesId", seriesId,
            "isCustom", true, "config", algorithmMap(posted)));
    }

    // --- Series roster -------------------------------------------------------

    /**
     * The roster joined to the register, so the page can render sail numbers
     * and names without a second call. A roster entry whose boat has been
     * removed from the register is dropped rather than rendered blank.
     */
    private Map<String, Object> rosterBody(String seriesId)
    {
        Roster roster = store.roster(seriesId);
        Map<String, Boat> boats = store.boats();
        List<Map<String, Object>> rows = new ArrayList<>();
        if (roster != null)
        {
            for (Roster.Entry e : roster.entries())
            {
                Boat b = boats.get(e.boatId());
                if (b == null)
                    continue;
                rows.add(mapOf("boatId", b.id(), "sailNumber", b.sailNumber(),
                    "name", b.name(), "designId", b.designId(),
                    "division", e.division(), "spinnaker", e.spinnaker(),
                    "startingTcf", e.startingTcf()));
            }
        }
        return mapOf("seriesId", seriesId, "entries", rows);
    }

    private void handleSaveRoster(HttpServletRequest req, HttpServletResponse resp,
                                  String seriesId) throws Exception
    {
        if (denyUnless(req, resp, Role.ADMIN))
            return;
        JsonNode body = MAPPER.readTree(req.getInputStream());
        JsonNode entries = body.isArray() ? body : body.path("entries");
        List<Roster.Entry> out = new ArrayList<>();
        for (JsonNode e : entries)
        {
            String boatId = text(e, "boatId");
            if (isBlank(boatId))
                continue;
            Boat boat = store.boats().get(boatId);
            // A boat entering a series has to be given a handicap: there is no such thing
            // as "the boat's TCF" to fall back on. 1.0 is the scratch default — visibly a
            // starting point rather than a considered figure.
            double tcf = e.hasNonNull("startingTcf") ? e.path("startingTcf").asDouble() : 1.0;
            Spinnaker spinnaker = e.hasNonNull("spinnaker")
                ? spinnakerOf(e.path("spinnaker"))
                : defaultSpinnakerFor(seriesId, boat);
            out.add(new Roster.Entry(boatId, tcf, text(e, "division"), spinnaker));
        }
        store.putRoster(new Roster(seriesId, out));
        writeJson(resp, mapOf("ok", true, "seriesId", seriesId, "saved", out.size()));
    }

    /**
     * The spinnaker a boat defaults to when entering a series: the series policy when it
     * has one, otherwise whether the hull can physically fly a kite.
     */
    private Spinnaker defaultSpinnakerFor(String seriesId, Boat boat)
    {
        Series series = store.series().get(seriesId);
        if (series != null)
        {
            Spinnaker byPolicy = series.spinnakerPolicy().defaultSpinnaker();
            if (byPolicy != null)
                return byPolicy;
        }
        return boat == null ? null : registry.defaultSpinnaker(boat.designId());
    }

    // --- Races ---------------------------------------------------------------

    private List<Map<String, Object>> allRaces()
    {
        Map<String, Series> series = store.series();
        return store.races().values().stream()
            .sorted(Comparator.comparing(Race::date,
                    Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparingInt(Race::number))
            .map(r -> {
                Series s = series.get(r.seriesId());
                Map<String, Object> row = new LinkedHashMap<>(raceMap(r));
                row.put("seriesName", s == null ? null : s.name());
                row.put("locked", isLocked(r.id()));
                row.put("hasStartSheet", store.startSheet(r.id()) != null);
                row.put("hasResults", hasCapturedResults(r.id()));
                return row;
            })
            .toList();
    }

    /**
     * Create or update a race.
     *
     * <p>A season is a run of dates a fixed interval apart, so the body may ask for the
     * whole run at once: {@code repeatDays} and {@code repeatCount} create that many
     * races, each that many days after the last. Omitted (or a count of 1) makes a single
     * race. An interval of 0 is allowed and means several races on one day, which is what
     * a regatta weekend looks like.
     */
    private void handleSaveRace(HttpServletRequest req, HttpServletResponse resp) throws Exception
    {
        if (denyUnless(req, resp, Role.ADMIN))
            return;
        JsonNode body = MAPPER.readTree(req.getInputStream());
        String seriesId = text(body, "seriesId");
        if (isBlank(seriesId) || !store.series().containsKey(seriesId))
        {
            badRequest(resp, "a known seriesId is required");
            return;
        }

        JinxConfig.Algorithm alg = algorithmFor(seriesId);
        LocalDate date = parseDate(text(body, "date"));
        String name = text(body, "name");
        LocalTime earliest = parseTime(text(body, "earliestStart"),
            LocalTime.parse(alg.earliestStart()));
        Integer target = body.hasNonNull("targetElapsedMinutes")
            ? body.path("targetElapsedMinutes").asInt() : alg.defaultRaceDuration();

        // An edit keeps the id it was given, so renaming a race never orphans its
        // entrants or times. Only a new race can be a repeating run.
        String id = text(body, "id");
        if (!isBlank(id))
        {
            Race race = new Race(id, seriesId, body.path("number").asInt(nextRaceNumber(seriesId)),
                name, date, earliest, target, body.path("abandoned").asBoolean(false));
            store.putRace(race);
            writeJson(resp, mapOf("ok", true, "created", 1, "race", raceMap(race)));
            return;
        }

        if (date == null)
        {
            badRequest(resp, "a race needs a date");
            return;
        }

        int count = Math.max(1, body.path("repeatCount").asInt(1));
        int everyDays = Math.max(0, body.path("repeatDays").asInt(0));
        if (count > MAX_RACES_PER_REQUEST)
        {
            badRequest(resp, "that would create " + count + " races; the most in one go is "
                + MAX_RACES_PER_REQUEST);
            return;
        }

        int number = body.path("number").asInt(nextRaceNumber(seriesId));
        List<Map<String, Object>> created = new ArrayList<>(count);
        for (int i = 0; i < count; i++)
        {
            LocalDate raceDate = date.plusDays((long)everyDays * i);
            // Several races on one day need distinct ids, and the id carries only the
            // date — so the race number is what separates them.
            String raceId = IdGenerator.generateRaceId(config.club().domain(), raceDate, number + i);
            Race race = new Race(raceId, seriesId, number + i, name, raceDate,
                earliest, target, false);
            store.putRace(race);
            created.add(raceMap(race));
        }

        writeJson(resp, mapOf("ok", true, "created", created.size(),
            "race", created.getFirst(), "races", created));
    }

    private int nextRaceNumber(String seriesId)
    {
        return store.racesInSeries(seriesId).stream()
            .mapToInt(Race::number).max().orElse(0) + 1;
    }

    /**
     * Everything the race page needs, in one response: the race, its series,
     * the entrants (with their TCFs), the captured times, the published start
     * sheet, any saved adjustments, and the derived lock state.
     *
     * <p>One call rather than the eight the SailSys-era page made. There is no
     * longer a slow remote to parallelise around — it is all one local read.
     */
    private void writeRaceBundle(HttpServletRequest req, HttpServletResponse resp,
        String raceId) throws Exception
    {
        Race race = store.races().get(raceId);
        if (race == null)
        {
            resp.setStatus(404);
            writeJson(resp, mapOf("error", "no such race: " + raceId));
            return;
        }
        Series series = store.series().get(race.seriesId());
        RaceEntrants entrants = store.entrants(raceId);
        List<Adjustment> adjustments = store.adjustments(raceId);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("race", raceMap(race));
        out.put("seriesName", series == null ? null : series.name());
        // The page shows the Spin column only where boats in the same series differ.
        out.put("spinnakerPolicy", series == null ? null : series.spinnakerPolicy().name());
        JinxConfig.Algorithm alg = algorithmFor(race.seriesId());
        out.put("algorithm", algorithmMap(alg));
        LocalTime sunset = alg.limitBySunset() ? sunsetFor(alg, race) : null;
        out.put("sunsetLocal", sunset == null ? null
            : String.format("%02d:%02d", sunset.getHour(), sunset.getMinute()));
        out.put("entrants", entrants);
        out.put("startSheet", store.startSheet(raceId));
        out.put("times", store.raceTimes(raceId));
        out.put("adjustments", adjustments);
        // The lifecycle is derived, never stored: saved adjustments lock the
        // race, and Unlock is deleting them. See Race's javadoc.
        out.put("locked", !adjustments.isEmpty());
        // The request, not null: with a login configured the role is read off the
        // session, and a handler that cannot say who is asking cannot answer this.
        out.put("role", currentRole(req).name());
        Optional<Race> next = store.nextRaceInSeries(raceId);
        out.put("nextRaceId", next.map(Race::id).orElse(null));
        out.put("previousRaceId", previousRaceId(race));
        writeJson(resp, out);
    }

    private String previousRaceId(Race race)
    {
        Race best = null;
        for (Race r : store.racesInSeries(race.seriesId()))
        {
            if (r.number() < race.number() && (best == null || r.number() > best.number()))
                best = r;
        }
        return best == null ? null : best.id();
    }

    private boolean isLocked(String raceId)
    {
        return !store.adjustments(raceId).isEmpty();
    }

    /**
     * True when something was actually captured for this race — a boat marked as having
     * come, or a finish recorded.
     *
     * <p>Not merely "a times file exists": saving the race page writes one whether or not
     * anything was entered, so the file's presence said "results captured" for a race
     * nobody had touched.
     */
    private boolean hasCapturedResults(String raceId)
    {
        RaceTimes times = store.raceTimes(raceId);
        if (times == null)
            return false;
        return times.times().values().stream()
            .anyMatch(t -> t.came() || (t.finish() != null && !t.finish().isBlank()));
    }

    // --- Entrants ------------------------------------------------------------

    /**
     * Replace a race's entrant list. The client sends the whole list — it is
     * forty rows — which keeps add, remove, and TCF edit as one operation and
     * makes it impossible to drop the TCFs of boats that weren't being edited.
     *
     * <p>A row with no {@code boatId} is a one-off visitor. A row whose
     * {@code boatId} is not in the register is rejected rather than silently
     * turned into a one-off.
     *
     * <p><b>Two operations share this endpoint, and they need different permissions.</b>
     * Which boats the race is scored over is the admin's; what those boats sailed on —
     * their TCF, their division, whether they are casual — is the race officer's. So the
     * role is decided from the diff rather than from the route: a list with the same
     * boats in it is an edit, a list with a boat more or fewer is a different race.
     *
     * <p>Checked here rather than by splitting the endpoint because the client sends the
     * whole list precisely so that add, remove and edit cannot be separated on the way
     * in — see above. Splitting it to carry the permission would reintroduce the partial
     * update that shape exists to prevent.
     */
    private void handleSaveEntrants(HttpServletRequest req, HttpServletResponse resp, String raceId)
        throws Exception
    {
        if (denyUnless(req, resp, Role.RACE_OFFICER))
            return;
        if (rejectIfLocked(resp, raceId))
            return;
        JsonNode body = MAPPER.readTree(req.getInputStream());
        JsonNode rows = body.isArray() ? body : body.path("entrants");
        if (!rows.isArray())
        {
            badRequest(resp, "entrants array required");
            return;
        }

        List<Entrant> entrants = new ArrayList<>();
        for (JsonNode row : rows)
        {
            String boatId = text(row, "boatId");
            double tcf = row.path("tcf").asDouble(1.0);
            if (isBlank(boatId))
            {
                entrants.add(Entrant.oneOff(text(row, "name"), text(row, "sailNumber"), tcf));
                continue;
            }
            Boat boat = store.boats().get(boatId);
            if (boat == null)
            {
                badRequest(resp, "unknown boat: " + boatId);
                return;
            }
            Entrant.EntryType type = entryTypeOf(row.path("entryType"),
                boat.casual() ? Entrant.EntryType.CASUAL : Entrant.EntryType.ROSTER);
            entrants.add(Entrant.fromBoat(boat, tcf, text(row, "division"),
                row.hasNonNull("spinnaker")
                    ? spinnakerOf(row.path("spinnaker"))
                    : registry.defaultSpinnaker(boat.designId()),
                type));
        }

        RaceEntrants existing = store.entrants(raceId);
        // The composition check, once the list has been parsed and the boat ids resolved:
        // comparing the raw request to the store would compare a sail number typed in a
        // browser against an id the registry minted.
        if (changesTheFleet(raceId, entrants) && denyUnless(req, resp, Role.ADMIN))
            return;

        RaceEntrants saved = new RaceEntrants(raceId, Instant.now(),
            tcfSourceOf(body.path("tcfSource"), existing),
            existing == null ? null : existing.sourceRaceId(),
            existing == null ? null : existing.sourceRaceNumber(),
            entrants);
        store.putEntrants(saved);
        writeJson(resp, mapOf("ok", true, "raceId", raceId, "entrants", saved));
    }

    /**
     * True when this list is a different set of boats from the one on file.
     *
     * <p>By boat id and as a set: reordering the entrants is the race officer's business
     * — the race page's manual ordering saves through here — and so is editing any of
     * them. Only membership is the admin's.
     *
     * <p>One-offs have no boat id, so they are counted rather than named. Adding or
     * removing one still changes the count, and no two one-offs can be told apart by
     * anything this endpoint receives.
     */
    private boolean changesTheFleet(String raceId, List<Entrant> proposed)
    {
        RaceEntrants existing = store.entrants(raceId);
        List<Entrant> before = existing == null ? List.of() : existing.entrants();
        if (idsOf(before).equals(idsOf(proposed)))
            return countWithoutId(before) != countWithoutId(proposed);
        return true;
    }

    private static Set<String> idsOf(List<Entrant> entrants)
    {
        return entrants.stream().map(Entrant::boatId).filter(id -> id != null && !id.isBlank())
            .collect(java.util.stream.Collectors.toSet());
    }

    private static long countWithoutId(List<Entrant> entrants)
    {
        return entrants.stream().filter(e -> e.boatId() == null || e.boatId().isBlank()).count();
    }

    /**
     * Seed a race's entrants. The first race of a series takes the roster and
     * its starting TCFs; any later race carries forward the previous race's
     * entrants at their current TCFs, which is what the fleet actually is until
     * handicaps are processed.
     *
     * <p>Refuses to overwrite an existing list — re-seeding a race that already
     * has times captured against it would be destructive, and the RO can add or
     * remove individual boats instead.
     */
    private void handleSeedEntrants(HttpServletRequest req, HttpServletResponse resp,
        String raceId) throws Exception
    {
        if (denyUnless(req, resp, Role.ADMIN))
            return;
        Race race = store.races().get(raceId);
        if (race == null)
        {
            resp.setStatus(404);
            writeJson(resp, mapOf("error", "no such race: " + raceId));
            return;
        }
        RaceEntrants existing = store.entrants(raceId);
        if (existing != null && !existing.entrants().isEmpty())
        {
            badRequest(resp, "race already has entrants — add or remove boats instead");
            return;
        }

        RaceEntrants seeded = seedFromPreviousRace(race)
            .orElseGet(() -> seedFromRoster(race));
        store.putEntrants(seeded);
        writeJson(resp, mapOf("ok", true, "raceId", raceId, "entrants", seeded));
    }

    private Optional<RaceEntrants> seedFromPreviousRace(Race race)
    {
        String prevId = previousRaceId(race);
        if (prevId == null)
            return Optional.empty();
        RaceEntrants prev = store.entrants(prevId);
        if (prev == null || prev.entrants().isEmpty())
            return Optional.empty();
        Race prevRace = store.races().get(prevId);
        // Only roster boats carry forward. One-offs have no register boat, and a casual
        // turned up once — seeding it would put a boat nobody expects on the start sheet.
        List<Entrant> carried = prev.entrants().stream()
            .filter(Entrant::seedsNextRace)
            .toList();
        return Optional.of(new RaceEntrants(race.id(), Instant.now(),
            RaceEntrants.TcfSource.CARRIED_FORWARD, prevId,
            prevRace == null ? null : prevRace.number(), carried));
    }

    private RaceEntrants seedFromRoster(Race race)
    {
        Roster roster = store.roster(race.seriesId());
        List<Entrant> entrants = new ArrayList<>();
        if (roster != null)
        {
            Map<String, Boat> boats = store.boats();
            for (Roster.Entry e : roster.entries())
            {
                Boat b = boats.get(e.boatId());
                if (b != null && b.active())
                    entrants.add(Entrant.fromRosterEntry(b, e));
            }
        }
        return new RaceEntrants(race.id(), Instant.now(),
            RaceEntrants.TcfSource.ROSTER, null, null, entrants);
    }

    // --- Start times ---------------------------------------------------------

    /**
     * Compute and publish the pursuit start sheet from the race's entrants and
     * their TCFs — wiki §4. This is the work SailSys used to do after a timing
     * PUT; it is now a local calculation that returns immediately.
     *
     * <p>Body may override {@code targetElapsedMinutes} and
     * {@code earliestStart}; both default to the race's own values. The sheet
     * is persisted because it gets published to the fleet: once boats know
     * their gun, a later TCF edit must not silently move it.
     */
    private void handleComputeStartTimes(HttpServletRequest req, HttpServletResponse resp,
                                         String raceId) throws Exception
    {
        if (denyUnless(req, resp, Role.RACE_OFFICER))
            return;
        Race race = store.races().get(raceId);
        if (race == null)
        {
            resp.setStatus(404);
            writeJson(resp, mapOf("error", "no such race: " + raceId));
            return;
        }
        RaceEntrants entrants = store.entrants(raceId);
        if (entrants == null || entrants.entrants().isEmpty())
        {
            badRequest(resp, "race has no entrants — seed them first");
            return;
        }

        JsonNode body = MAPPER.readTree(req.getInputStream());
        JinxConfig.Algorithm alg = algorithmFor(race.seriesId());
        int target = body.hasNonNull("targetElapsedMinutes")
            ? body.path("targetElapsedMinutes").asInt()
            : (race.targetElapsedMinutes() != null
                ? race.targetElapsedMinutes() : alg.defaultRaceDuration());
        LocalTime earliest = parseTime(text(body, "earliestStart"),
            race.earliestStart() != null
                ? race.earliestStart() : LocalTime.parse(alg.earliestStart()));

        // Cap the duration so the fleet is expected home by sundown. Done here rather
        // than when the target is typed, because it depends on the race date and the
        // earliest start, and both can still change up to this point.
        int requested = target;
        LocalTime sunset = alg.limitBySunset() ? sunsetFor(alg, race) : null;
        target = capBySunset(target, earliest, sunset);
        if (target <= 0)
        {
            // Sunset is at or before the earliest start: there is no daylight to cap
            // into. Emitting a nought-minute target would produce a start sheet with
            // every boat on the same gun, which is worse than refusing — the race as
            // described cannot be sailed, and the RO needs to move the start or the date.
            badRequest(resp, "sunset on " + race.date() + " is at "
                + String.format("%02d:%02d", sunset.getHour(), sunset.getMinute())
                + ", before the earliest start of " + earliest.toString().substring(0, 5)
                + " — there is no daylight to race in. Move the start earlier, "
                + "or turn off the sunset limit for this series.");
            return;
        }

        // The engine wants an id to key its answer by and the TCF in force. Entrants are
        // not register boats — a one-off has no register entry at all — and the TCF
        // belongs to the entry rather than the hull, so a Competitor is exactly the pair
        // the algorithm needs.
        List<Competitor> forEngine = new ArrayList<>();
        for (int i = 0; i < entrants.entrants().size(); i++)
        {
            Entrant e = entrants.entrants().get(i);
            forEngine.add(new Competitor(entrantKey(e, i), e.tcf()));
        }
        Race forEngineRace = new Race(race.id(), race.seriesId(), race.number(), race.name(),
            race.date(), earliest, target, race.abandoned());

        List<StartTime> starts = new ArrayList<>(engine.computeStartTimes(forEngine, forEngineRace));
        // Slowest boat first: the order they start in, and the order the
        // start-offset report prints.
        starts.sort(Comparator.comparing(StartTime::startTime));

        StartSheet sheet = new StartSheet(raceId, Instant.now(), target, earliest, starts);
        store.putStartSheet(sheet);

        // Persist the inputs on the race too, so reopening it shows what the
        // published sheet was actually computed from.
        store.putRace(new Race(race.id(), race.seriesId(), race.number(), race.name(),
            race.date(), earliest, target, race.abandoned()));

        Map<String, Object> out = mapOf("ok", true, "raceId", raceId, "startSheet", sheet);
        out.put("requestedTargetMinutes", requested);
        out.put("limitedBySunset", target != requested);
        out.put("sunsetLocal", sunset == null ? null
            : String.format("%02d:%02d", sunset.getHour(), sunset.getMinute()));
        writeJson(resp, out);
    }

    /**
     * The key a start time is reported against. Register boats use their boat
     * id; one-offs have none, so they get a positional key that is stable for
     * as long as the entrant list is.
     */
    private static String entrantKey(Entrant e, int index)
    {
        return e.boatId() != null ? e.boatId() : ("one-off-" + index);
    }

    // --- Captured times ------------------------------------------------------

    private void handleSaveRaceTimes(HttpServletRequest req, HttpServletResponse resp, String raceId)
        throws Exception
    {
        if (denyUnless(req, resp, Role.RACE_OFFICER))
            return;
        if (rejectIfLocked(resp, raceId))
            return;
        RaceTimes incoming = MAPPER.readValue(req.getInputStream(), RaceTimes.class);
        // Trust the URL's raceId over whatever the body claimed.
        store.putRaceTimes(raceId, new RaceTimes(raceId, incoming.boatOrder(),
            incoming.dutyBoatId(), incoming.times()));
        writeJson(resp, mapOf("ok", true, "raceId", raceId));
    }

    // --- Course planning -----------------------------------------------------

    /**
     * Turn a target race duration into a course length: a boat sails
     * {@code TCF × V₀ × hours} nautical miles, and the course is sized to the
     * slowest boat in the fleet so nobody is still out there after dark.
     *
     * <p>Body: {@code {targetElapsedMinutes?, slowestTcf?}}. Both default from
     * the race and its entrants. When the series is configured with
     * {@code limitBySunset}, the duration is capped so the slowest boat is
     * expected to finish by sunset on the race date.
     */
    /**
     * The target elapsed time actually used, capped so the slowest boat is expected to be
     * home by sunset.
     *
     * <p>The cap belongs on the duration, not on a course length: what the RO controls on
     * the night is how long the race is meant to take, and sailing past sundown is the
     * thing being prevented. If sunset falls at or before the earliest start — an
     * out-of-season date where it is already dark — the cap still engages, clamped to
     * zero and flagged, rather than silently doing nothing.
     */
    static int capBySunset(int requested, LocalTime earliestStart, LocalTime sunset)
    {
        if (sunset == null || earliestStart == null)
            return requested;
        long available = java.time.Duration.between(earliestStart, sunset).toMinutes();
        return requested > available ? (int)Math.max(0, available) : requested;
    }

    private LocalTime sunsetFor(JinxConfig.Algorithm alg, Race race)
    {
        if (!alg.limitBySunset() || race.date() == null)
            return null;
        try
        {
            return SolarTimes.sunsetLocal(alg.latitude(), alg.longitude(), race.date(),
                ZoneId.of(config.club().timezone()));
        }
        catch (Exception e)
        {
            LOG.warn("Sunset computation failed for race {}: {}", race.id(), e.toString());
            return null;
        }
    }

    /** The (possibly sunset-capped) duration and the course length it implies. */
    /**
     * Pure course-length calculation. A boat's predicted speed is
     * {@code TCF × v0Knots}, so over {@code t} hours it sails
     * {@code TCF × v0 × t} nm; the course is sized to {@code slowestTcf} and
     * rounded to 0.1 nm.
     *
     * <p>When {@code limitBySunset} and a {@code sunset} are given, the
     * duration is capped so {@code earliestStart + duration ≤ sunset}. If
     * sunset is at or before {@code earliestStart} — an out-of-season date
     * where it is already dark at the start — the cap still engages, clamped to
     * zero and flagged, rather than silently doing nothing.
     */
    // --- Handicaps -----------------------------------------------------------

    /**
     * Run the Jinx algorithm against a client-supplied snapshot of the race and
     * return the adjustments. Computes only — nothing is written, so the admin
     * can preview before committing.
     *
     * <p>The snapshot comes from the client rather than the store because the
     * client is where the scoring primitives of wiki §5.1 live: effective
     * start, OCS handling, scored elapsed, and the flag overrides the RO may
     * have applied but not yet saved. Body:
     * <pre>{@code
     *   { "targetElapsedMinutes": 90,
     *     "boats": [ { "boatId": "b-1", "currentTcf": 1.0,
     *                  "status": "FIN", "elapsedMinutes": 85.0,
     *                  "finishPosition": 1 }, ... ] }
     * }</pre>
     */
    private void handleProcessHandicaps(HttpServletRequest req, HttpServletResponse resp,
                                        String raceId) throws Exception
    {
        if (denyUnless(req, resp, Role.RACE_OFFICER))
            return;
        Race race = store.races().get(raceId);
        JsonNode body = MAPPER.readTree(req.getInputStream());
        JinxConfig.Algorithm alg = algorithmFor(race == null ? null : race.seriesId());

        int tTarget = body.path("targetElapsedMinutes").asInt(
            race != null && race.targetElapsedMinutes() != null
                ? race.targetElapsedMinutes() : alg.defaultRaceDuration());

        JsonNode boatsNode = body.path("boats");
        if (!boatsNode.isArray() || boatsNode.isEmpty())
        {
            badRequest(resp, "boats array required");
            return;
        }

        List<Competitor> boats = new ArrayList<>(boatsNode.size());
        Map<String, Result> results = new LinkedHashMap<>(boatsNode.size());
        // All finishers carry a corrected finish or none of them do. A mixture would be
        // far worse than the absence: the boats without one would sit at a few minutes
        // past midnight while the rest sat at a real time of day, so the fleet's gaps
        // would come out as the ~19 hours between those two frames — near-identical for
        // everybody, quietly flattening the giveback to an even split with no error.
        boolean missingCorrectedFinish = false;
        for (JsonNode b : boatsNode)
        {
            String boatId = text(b, "boatId");
            if (isBlank(boatId))
                continue;
            double tcf = b.path("currentTcf").asDouble(1.0);
            // Absent means seeded: every caller before the flag meant that, and treating
            // a missing field as "casual" would quietly drop the whole fleet.
            boats.add(new Competitor(boatId, tcf, b.path("seeded").asBoolean(true)));

            FinishStatus status;
            try
            {
                status = FinishStatus.valueOf(b.path("status").asText("DNC").toUpperCase());
            }
            catch (IllegalArgumentException ex)
            {
                status = FinishStatus.DNC;
            }
            // The engine reads elapsed as finish − actualStart. We only have
            // the precomputed elapsed minutes here, so encode it as midnight
            // plus that many seconds and let the Duration maths come out right.
            //
            // These two are NOT wall-clock times and must not be read as such — only
            // their difference means anything. The real finish travels separately, as
            // correctedFinishSeconds, because reconstructing a start from it would put
            // a race finishing near midnight on the wrong side of the wrap and hand the
            // engine a negative elapsed time.
            LocalTime startT = LocalTime.MIDNIGHT;
            LocalTime finishT = null;
            if (status == FinishStatus.FIN && b.has("elapsedMinutes"))
                finishT = startT.plusSeconds(Math.round(b.path("elapsedMinutes").asDouble(0) * 60.0));

            Integer finishPosition = b.hasNonNull("finishPosition")
                ? b.path("finishPosition").asInt() : null;
            Integer correctedFinish = b.hasNonNull("correctedFinishSeconds")
                ? b.path("correctedFinishSeconds").asInt() : null;
            if (status == FinishStatus.FIN && correctedFinish == null)
                missingCorrectedFinish = true;
            results.put(boatId, new Result(boatId, status, startT, finishT, null,
                finishPosition, correctedFinish));
        }

        if (missingCorrectedFinish)
        {
            LOG.warn("process-handicaps for {}: at least one finisher has no "
                + "correctedFinishSeconds, so the whole fleet falls back to elapsed order "
                + "for the giveback. Expected only from an out-of-date page.", raceId);
            results.replaceAll((id, r) -> new Result(r.boatId(), r.status(), r.actualStart(),
                r.finish(), r.penaltyMinutes(), r.finishPosition(), null));
        }

        Race forEngine = new Race(raceId, race == null ? null : race.seriesId(), 0, "",
            null, null, tTarget, false);
        List<Adjustment> adjustments =
            new PursuitHandicapEngine(alg).processResults(boats, forEngine, results);

        writeJson(resp, mapOf(
            "raceId", raceId,
            "targetElapsedMinutes", tTarget,
            "algorithm", algorithmMap(alg),
            "adjustments", adjustments));
    }

    /**
     * Commit the computed adjustments and carry the new TCFs into the next
     * race. Two writes:
     * <ol>
     *   <li>the adjustments themselves, which also lock this race, and</li>
     *   <li>the next race's entrant list, seeded from this race's entrants at
     *       their {@link Adjustment#newTcf()}.</li>
     * </ol>
     *
     * <p>Boats that did not score a handicap here (one-offs) are not carried.
     * If this is the last race in the series there is nothing to carry to, and
     * only the first write happens.
     */
    private void handleSaveHandicaps(HttpServletRequest req, HttpServletResponse resp, String raceId)
        throws Exception
    {
        if (denyUnless(req, resp, Role.RACE_OFFICER))
            return;
        JsonNode body = MAPPER.readTree(req.getInputStream());
        JsonNode arr = body.isArray() ? body : body.path("adjustments");
        if (!arr.isArray() || arr.isEmpty())
        {
            badRequest(resp, "adjustments array required");
            return;
        }
        List<Adjustment> adjustments =
            MAPPER.convertValue(arr, new TypeReference<List<Adjustment>>() { });
        store.putAdjustments(raceId, adjustments);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        result.put("raceId", raceId);
        result.put("saved", adjustments.size());

        Race race = store.races().get(raceId);
        Optional<Race> next = store.nextRaceInSeries(raceId);
        if (next.isPresent())
        {
            RaceEntrants carried = carryForward(race, next.get(), adjustments);
            store.putEntrants(carried);
            result.put("nextRaceId", next.get().id());
            result.put("carriedEntrants", carried.entrants().size());
        }

        store.appendAudit(new AuditEntry(Instant.now(), raceId, "save-handicaps",
            auditUser(req), 0.0, adjustments.stream().mapToDouble(Adjustment::penaltyMinutes).sum(),
            adjustments,
            next.map(r -> "carried to race " + r.number()).orElse("last race in series")));

        writeJson(resp, result);
    }

    /**
     * Build the next race's entrants from this race's entrants and the computed
     * adjustments. Identity comes from this race — the adjustments only carry a
     * boat id and a TCF.
     */
    private RaceEntrants carryForward(Race race, Race next, List<Adjustment> adjustments)
    {
        Map<String, Double> newTcfs = new LinkedHashMap<>();
        for (Adjustment a : adjustments)
            newTcfs.put(a.boatId(), a.newTcf());

        RaceEntrants current = store.entrants(race == null ? "" : race.id());
        List<Entrant> carried = new ArrayList<>();
        if (current != null)
        {
            for (Entrant e : current.entrants())
            {
                if (!e.seedsNextRace())
                    continue;
                Double tcf = newTcfs.get(e.boatId());
                carried.add(new Entrant(e.boatId(), e.sailNumber(), e.name(), e.division(),
                    e.designId(), e.spinnaker(), tcf != null ? tcf : e.tcf(), e.entryType()));
            }
        }
        return new RaceEntrants(next.id(), Instant.now(),
            RaceEntrants.TcfSource.CARRIED_FORWARD,
            race == null ? null : race.id(),
            race == null ? null : race.number(),
            carried);
    }

    /**
     * Unlock a race: drop its saved adjustments so times and TCFs become
     * editable again and the handicaps can be reprocessed.
     *
     * <p>The next race's carried-forward TCFs are deliberately left alone. They
     * are corrected by re-running Save Handicaps, and silently rewriting a race
     * the user did not ask about would be worse than leaving a stale value they
     * can see.
     */
    private void handleUnlockRace(HttpServletRequest req, HttpServletResponse resp, String raceId)
        throws Exception
    {
        if (denyUnless(req, resp, Role.RACE_OFFICER))
            return;
        boolean removed = store.deleteAdjustments(raceId);
        if (removed)
        {
            store.appendAudit(new AuditEntry(Instant.now(), raceId, "unlock",
                auditUser(req), 0.0, 0.0, List.of(),
                "adjustments discarded for reprocessing"));
        }
        writeJson(resp, mapOf("ok", true, "raceId", raceId, "unlocked", removed));
    }

    // --- Helpers -------------------------------------------------------------

    private JinxConfig.Algorithm algorithmFor(String seriesId)
    {
        JinxConfig.Algorithm saved = (seriesId == null) ? null : store.seriesConfig(seriesId);
        return saved != null ? saved : config.algorithm();
    }

    private static Map<String, Object> algorithmMap(JinxConfig.Algorithm a)
    {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("penaltyList", a.penaltyList());
        m.put("defaultRaceDuration", a.defaultRaceDuration());
        m.put("penaltyScaling", a.penaltyScaling().name());
        m.put("givebackGamma", a.givebackGamma());
        m.put("dnfInRaceDuration", a.dnfInRaceDuration());
        m.put("variant", a.asVariant().map(Enum::name).orElse(null));
        m.put("dnfAllowance", a.dnfAllowance());
        m.put("earliestStart", a.earliestStart());
        m.put("latitude", a.latitude());
        m.put("longitude", a.longitude());
        m.put("limitBySunset", a.limitBySunset());
        return m;
    }

    private static Map<String, Object> raceMap(Race r)
    {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.id());
        m.put("seriesId", r.seriesId());
        m.put("number", r.number());
        m.put("name", r.name());
        m.put("date", r.date() == null ? null : r.date().toString());
        m.put("earliestStart", r.earliestStart() == null ? null : r.earliestStart().toString());
        m.put("targetElapsedMinutes", r.targetElapsedMinutes());
        m.put("abandoned", r.abandoned());
        return m;
    }

    /**
     * Refuse a mutation on a locked race. Times and entrants are frozen once
     * the handicaps have been processed, because changing them would make the
     * saved adjustments — and the next race's TCFs — a lie.
     */
    private boolean rejectIfLocked(HttpServletResponse resp, String raceId) throws IOException
    {
        if (!isLocked(raceId))
            return false;
        resp.setStatus(409);
        writeJson(resp, mapOf("error",
            "race is locked: its handicaps have been processed. Unlock it to make changes."));
        return true;
    }

    /**
     * True when this caller may not do this, having already written the refusal.
     *
     * <p>Returns rather than throws, and the caller must act on it —
     * {@code if (denyUnless(req, resp, Role.ADMIN)) return;} — matching
     * {@link #rejectIfLocked}. An ancestor wrote a 403 and returned void, so the caller
     * read the status, ignored it, and did the work anyway: harmless while every request
     * was an admin, and a hole the moment one was not.
     *
     * <p><b>401 for a visitor, 403 for a club account that is not an admin.</b> The codes
     * are not decoration: they are the difference between "sign in and this will work"
     * and "signing in will not help", and the page shows a sign-in button for exactly one
     * of them. A blanket 403 would put a login button in front of a race officer who is
     * already logged in.
     */
    private boolean denyUnless(HttpServletRequest req, HttpServletResponse resp, Role required)
        throws IOException
    {
        Role role = currentRole(req);
        if (role.atLeast(required))
            return false;
        if (role == Role.VIEWER)
        {
            resp.setStatus(401);
            writeJson(resp, mapOf(
                "error", "sign in with a " + clubDomainForMessage() + " account to do that",
                "loginPath", JinxSecurityHandler.LOGIN_PATH));
        }
        else
        {
            resp.setStatus(403);
            writeJson(resp, mapOf("error", "this needs an administrator"));
        }
        return true;
    }

    /**
     * Who to record against an audit entry, or null when nobody can be named.
     *
     * <p>Deliberately the signed-in address rather than {@link #currentRole}: the log
     * answers "who unlocked race 4", and a role answers a different question. Null when
     * authentication is off, and null too for the {@code allowLoopback} exemption — that
     * request is an admin by configuration, not by identity, and there is no more honest
     * name for it than none.
     */
    private String auditUser(HttpServletRequest req)
    {
        return auth == null || !auth.enabled() ? null : SignedIn.of(req, auth).email();
    }

    private String clubDomainForMessage()
    {
        String domain = auth == null ? null : auth.allowedDomain();
        return domain == null ? "club" : domain;
    }

    /**
     * Who the browser is signed in as, so the nav bar can say so and the UI can hide
     * what this caller cannot do.
     *
     * <p>Only what the page needs: the address, the display name, and the two booleans.
     * No token, no claim dump — none of it is the browser's business, and a token echoed
     * into a page is a token in somebody's screenshot.
     */
    private Map<String, Object> whoami(HttpServletRequest req)
    {
        SignedIn who = SignedIn.of(req, auth);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("authEnabled", auth != null && auth.enabled());
        out.put("signedIn", who.isSignedIn());
        out.put("email", who.email());
        out.put("name", who.name());
        out.put("admin", who.admin());
        Role role = currentRole(req);
        out.put("role", role.name());
        // Named rather than derived in the page: what a role may do is the server's to
        // say, and a page that worked it out from the name would be a second answer.
        out.put("canEdit", role.atLeast(Role.RACE_OFFICER));
        out.put("logoutPath", "/auth/logout");
        out.put("loginPath", JinxSecurityHandler.LOGIN_PATH);
        return out;
    }

    private static String path(HttpServletRequest req)
    {
        String path = req.getPathInfo();
        return path == null ? "/" : path;
    }

    private static String text(JsonNode node, String field)
    {
        JsonNode v = node.path(field);
        return (v.isMissingNode() || v.isNull()) ? null : v.asText();
    }

    private static boolean isBlank(String s)
    {
        return s == null || s.isBlank();
    }

    /** Parse an enum by name, falling back rather than failing on an unknown value. */
    private static <E extends Enum<E>> E enumOf(Class<E> type, String name, E fallback)
    {
        if (isBlank(name))
            return fallback;
        try
        {
            return Enum.valueOf(type, name.trim().toUpperCase());
        }
        catch (IllegalArgumentException e)
        {
            return fallback;
        }
    }

    private static Spinnaker spinnakerOf(JsonNode node)
    {
        if (node.isMissingNode() || node.isNull())
            return Spinnaker.S;
        try
        {
            return Spinnaker.valueOf(node.asText().toUpperCase());
        }
        catch (IllegalArgumentException e)
        {
            return Spinnaker.S;
        }
    }

    private static Entrant.EntryType entryTypeOf(JsonNode node, Entrant.EntryType fallback)
    {
        if (node.isMissingNode() || node.isNull())
            return fallback;
        try
        {
            return Entrant.EntryType.valueOf(node.asText().toUpperCase());
        }
        catch (IllegalArgumentException e)
        {
            return fallback;
        }
    }

    /**
     * The source to stamp on a saved entrant list. An explicit value wins;
     * otherwise a list that already existed is being edited, so it becomes a
     * manual edit, and a brand new one is a roster seed.
     */
    private static RaceEntrants.TcfSource tcfSourceOf(JsonNode node, RaceEntrants existing)
    {
        if (!node.isMissingNode() && !node.isNull())
        {
            try
            {
                return RaceEntrants.TcfSource.valueOf(node.asText().toUpperCase());
            }
            catch (IllegalArgumentException ignored)
            {
                // fall through to the derived answer
            }
        }
        return existing == null
            ? RaceEntrants.TcfSource.ROSTER
            : RaceEntrants.TcfSource.MANUAL_EDIT;
    }

    private static LocalDate parseDate(String s)
    {
        if (isBlank(s))
            return null;
        try
        {
            return LocalDate.parse(s.length() > 10 ? s.substring(0, 10) : s);
        }
        catch (Exception e)
        {
            return null;
        }
    }

    private static LocalTime parseTime(String s, LocalTime fallback)
    {
        if (isBlank(s))
            return fallback;
        try
        {
            return LocalTime.parse(s.length() == 5 ? s + ":00" : s);
        }
        catch (Exception e)
        {
            return fallback;
        }
    }

    /** Null-tolerant map builder — {@link Map#of} rejects null values. */
    private static Map<String, Object> mapOf(Object... kv)
    {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2)
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        return m;
    }

    private void badRequest(HttpServletResponse resp, String message) throws IOException
    {
        resp.setStatus(400);
        writeJson(resp, mapOf("error", message));
    }

    private void fail(HttpServletResponse resp, Exception e) throws IOException
    {
        LOG.warn("API request failed", e);
        resp.setStatus(500);
        writeJson(resp, mapOf("error", String.valueOf(e.getMessage())));
    }

    private static void writeJson(HttpServletResponse resp, Object body) throws IOException
    {
        MAPPER.writeValue(resp.getWriter(), body);
    }
}
