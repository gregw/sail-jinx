package org.mortbay.sailing.jinx.server;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
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
import org.mortbay.sailing.jinx.config.JinxConfig;
import org.mortbay.sailing.jinx.model.Adjustment;
import org.mortbay.sailing.jinx.model.AuditEntry;
import org.mortbay.sailing.jinx.model.Boat;
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
 *   POST   /api/boats                         create or update a boat
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
 *   POST   /api/races/{id}/start-times        compute and publish the pursuit start sheet
 *   GET    /api/races/{id}/times              RO-captured came / start / finish
 *   POST   /api/races/{id}/times              save them
 *   POST   /api/races/{id}/course-plan        target duration to course length
 *   POST   /api/races/{id}/process-handicaps  run the Jinx algorithm (computes, saves nothing)
 *   POST   /api/races/{id}/save-handicaps     save adjustments, carry TCFs to the next race
 *   DELETE /api/races/{id}/adjustments        unlock the race for reprocessing
 *   GET    /api/audit                         the audit log
 * </pre>
 *
 * <h2>Authorisation</h2>
 * There is none. See {@link #currentRole} — the admin / race-officer split
 * survives as a concept so the UI can gate destructive actions, but every
 * request is currently treated as an admin because the app runs on one machine
 * on one desk. This has to change before it is hosted anywhere with a network
 * around it.
 */
public class ApiServlet extends HttpServlet
{
    private static final Logger LOG = LoggerFactory.getLogger(ApiServlet.class);

    private static final Pattern SERIES_CONFIG = Pattern.compile("/series/([^/]+)/config");
    private static final Pattern SERIES_ROSTER = Pattern.compile("/series/([^/]+)/roster");
    private static final Pattern SERIES_RACES = Pattern.compile("/series/([^/]+)/races");
    private static final Pattern RACE = Pattern.compile("/races/([^/]+)");
    private static final Pattern RACE_ENTRANTS = Pattern.compile("/races/([^/]+)/entrants");
    private static final Pattern RACE_ENTRANTS_SEED = Pattern.compile("/races/([^/]+)/entrants/seed");
    private static final Pattern RACE_START_TIMES = Pattern.compile("/races/([^/]+)/start-times");
    private static final Pattern RACE_TIMES = Pattern.compile("/races/([^/]+)/times");
    private static final Pattern RACE_COURSE_PLAN = Pattern.compile("/races/([^/]+)/course-plan");
    private static final Pattern RACE_PROCESS_HANDICAPS = Pattern.compile("/races/([^/]+)/process-handicaps");
    private static final Pattern RACE_SAVE_HANDICAPS = Pattern.compile("/races/([^/]+)/save-handicaps");
    private static final Pattern RACE_ADJUSTMENTS = Pattern.compile("/races/([^/]+)/adjustments");

    private static final JsonMapper MAPPER = JsonMapper.builder()
        .addModule(new JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        .build();

    private final JinxConfig config;
    private final JsonStore store;
    private final HandicapEngine engine;
    private final String version;

    public ApiServlet(JinxConfig config, JsonStore store, HandicapEngine engine, String version)
    {
        this.config = config;
        this.store = store;
        this.engine = engine;
        this.version = version;
    }

    /**
     * Who is asking. Always {@link Role#ADMIN}: sail-jinx has no login, and the
     * single machine it runs on is the security boundary.
     *
     * <p>This exists as a seam rather than being inlined so that adding
     * authentication later is one method, not a hunt through the servlet. The
     * role checks it feeds are real and are already in the right places.
     */
    static Role currentRole(HttpServletRequest req)
    {
        return Role.ADMIN;
    }

    /** What a caller is allowed to do. */
    public enum Role
    {
        /** Can do everything, including editing TCFs and processing handicaps. */
        ADMIN,
        /** Can capture times and results, but not touch handicaps. */
        RACE_OFFICER
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
                case "/boats" -> writeJson(resp, sortedBoats());
                case "/series" -> writeJson(resp, sortedSeries());
                case "/races" -> writeJson(resp, allRaces());
                case "/audit" -> writeJson(resp, store.audit());
                default -> doGetPath(path, resp);
            }
        }
        catch (Exception e)
        {
            fail(resp, e);
        }
    }

    private void doGetPath(String path, HttpServletResponse resp) throws Exception
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
            writeRaceBundle(resp, m.group(1));
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
            handleSeedEntrants(resp, m.group(1));
        else if ((m = RACE_ENTRANTS.matcher(path)).matches())
            handleSaveEntrants(req, resp, m.group(1));
        else if ((m = RACE_START_TIMES.matcher(path)).matches())
            handleComputeStartTimes(req, resp, m.group(1));
        else if ((m = RACE_TIMES.matcher(path)).matches())
            handleSaveRaceTimes(req, resp, m.group(1));
        else if ((m = RACE_COURSE_PLAN.matcher(path)).matches())
            handleCoursePlan(req, resp, m.group(1));
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
        out.put("club", mapOf("name", config.club().name(), "timezone", config.club().timezone()));
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
     * Create or update a register boat. A body without an {@code id} mints one;
     * with an {@code id} it overwrites. There is no delete — retiring a boat is
     * {@code active: false}, because races it already sailed still name it.
     */
    private void handleSaveBoat(HttpServletRequest req, HttpServletResponse resp) throws Exception
    {
        requireAdmin(resp);
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
            id = mintId("b");

        Boat boat = new Boat(
            id,
            sailNumber == null ? "" : sailNumber.trim(),
            name == null ? "" : name.trim(),
            text(body, "division"),
            text(body, "designId"),
            spinnakerOf(body.path("spinnaker")),
            body.path("currentTcf").asDouble(1.0),
            body.path("casual").asBoolean(false),
            !body.has("active") || body.path("active").asBoolean(true),
            text(body, "notes"));
        store.putBoat(boat);
        writeJson(resp, mapOf("ok", true, "boat", boat));
    }

    // --- Series --------------------------------------------------------------

    private List<Series> sortedSeries()
    {
        return store.series().values().stream()
            .sorted(Comparator.comparing(Series::name, String.CASE_INSENSITIVE_ORDER))
            .toList();
    }

    private void handleSaveSeries(HttpServletRequest req, HttpServletResponse resp) throws Exception
    {
        requireAdmin(resp);
        JsonNode body = MAPPER.readTree(req.getInputStream());
        String name = text(body, "name");
        if (isBlank(name))
        {
            badRequest(resp, "name is required");
            return;
        }
        String id = text(body, "id");
        if (isBlank(id))
            id = mintId("s");

        Series s = new Series(id, name.trim(), body.path("archived").asBoolean(false));
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
        requireAdmin(resp);
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
                    "name", b.name(), "division", b.division(),
                    "spinnaker", b.spinnaker(), "startingTcf", e.startingTcf()));
            }
        }
        return mapOf("seriesId", seriesId, "entries", rows);
    }

    private void handleSaveRoster(HttpServletRequest req, HttpServletResponse resp,
                                  String seriesId) throws Exception
    {
        requireAdmin(resp);
        JsonNode body = MAPPER.readTree(req.getInputStream());
        JsonNode entries = body.isArray() ? body : body.path("entries");
        List<Roster.Entry> out = new ArrayList<>();
        for (JsonNode e : entries)
        {
            String boatId = text(e, "boatId");
            if (isBlank(boatId))
                continue;
            Boat boat = store.boats().get(boatId);
            // Default a missing starting TCF to the register's seed value, so
            // adding a boat to a roster is one click.
            double tcf = e.hasNonNull("startingTcf")
                ? e.path("startingTcf").asDouble()
                : (boat != null ? boat.currentTcf() : 1.0);
            out.add(new Roster.Entry(boatId, tcf));
        }
        store.putRoster(new Roster(seriesId, out));
        writeJson(resp, mapOf("ok", true, "seriesId", seriesId, "saved", out.size()));
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
                row.put("hasResults", store.raceTimes(r.id()) != null);
                return row;
            })
            .toList();
    }

    private void handleSaveRace(HttpServletRequest req, HttpServletResponse resp) throws Exception
    {
        requireAdmin(resp);
        JsonNode body = MAPPER.readTree(req.getInputStream());
        String seriesId = text(body, "seriesId");
        if (isBlank(seriesId) || !store.series().containsKey(seriesId))
        {
            badRequest(resp, "a known seriesId is required");
            return;
        }
        String id = text(body, "id");
        if (isBlank(id))
            id = mintId("r");

        JinxConfig.Algorithm alg = algorithmFor(seriesId);
        Race race = new Race(
            id,
            seriesId,
            body.path("number").asInt(nextRaceNumber(seriesId)),
            text(body, "name"),
            parseDate(text(body, "date")),
            parseTime(text(body, "earliestStart"), LocalTime.parse(alg.earliestStart())),
            body.hasNonNull("targetElapsedMinutes")
                ? body.path("targetElapsedMinutes").asInt() : alg.idealRaceDuration(),
            body.hasNonNull("courseLengthNm") ? body.path("courseLengthNm").asDouble() : null,
            body.path("abandoned").asBoolean(false));
        store.putRace(race);
        writeJson(resp, mapOf("ok", true, "race", raceMap(race)));
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
    private void writeRaceBundle(HttpServletResponse resp, String raceId) throws Exception
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
        out.put("algorithm", algorithmMap(algorithmFor(race.seriesId())));
        out.put("entrants", entrants);
        out.put("startSheet", store.startSheet(raceId));
        out.put("times", store.raceTimes(raceId));
        out.put("adjustments", adjustments);
        // The lifecycle is derived, never stored: saved adjustments lock the
        // race, and Unlock is deleting them. See Race's javadoc.
        out.put("locked", !adjustments.isEmpty());
        out.put("role", currentRole(null));
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

    // --- Entrants ------------------------------------------------------------

    /**
     * Replace a race's entrant list. The client sends the whole list — it is
     * forty rows — which keeps add, remove, and TCF edit as one operation and
     * makes it impossible to drop the TCFs of boats that weren't being edited.
     *
     * <p>A row with no {@code boatId} is a one-off visitor. A row whose
     * {@code boatId} is not in the register is rejected rather than silently
     * turned into a one-off.
     */
    private void handleSaveEntrants(HttpServletRequest req, HttpServletResponse resp, String raceId)
        throws Exception
    {
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
            entrants.add(Entrant.fromBoat(boat, tcf, type));
        }

        RaceEntrants existing = store.entrants(raceId);
        RaceEntrants saved = new RaceEntrants(raceId, Instant.now(),
            tcfSourceOf(body.path("tcfSource"), existing),
            existing == null ? null : existing.sourceRaceId(),
            existing == null ? null : existing.sourceRaceNumber(),
            entrants);
        store.putEntrants(saved);
        writeJson(resp, mapOf("ok", true, "raceId", raceId, "entrants", saved));
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
    private void handleSeedEntrants(HttpServletResponse resp, String raceId) throws Exception
    {
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
        // One-offs don't carry forward: they had no register boat to carry.
        List<Entrant> carried = prev.entrants().stream()
            .filter(Entrant::scoresHandicap)
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
                    entrants.add(Entrant.fromBoat(b, e.startingTcf()));
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
                ? race.targetElapsedMinutes() : alg.idealRaceDuration());
        LocalTime earliest = parseTime(text(body, "earliestStart"),
            race.earliestStart() != null
                ? race.earliestStart() : LocalTime.parse(alg.earliestStart()));

        // The engine works in Boats. Entrants are not register boats — a
        // one-off has no register entry at all — so hand it the two fields it
        // actually reads: the id it should key the answer by, and the TCF.
        List<Boat> forEngine = new ArrayList<>();
        for (int i = 0; i < entrants.entrants().size(); i++)
        {
            Entrant e = entrants.entrants().get(i);
            forEngine.add(new Boat(entrantKey(e, i), e.sailNumber(), e.name(),
                e.division(), e.designId(), e.spinnaker(), e.tcf(), false, true, null));
        }
        Race forEngineRace = new Race(race.id(), race.seriesId(), race.number(), race.name(),
            race.date(), earliest, target, race.courseLengthNm(), race.abandoned());

        List<StartTime> starts = new ArrayList<>(engine.computeStartTimes(forEngine, forEngineRace));
        // Slowest boat first: the order they start in, and the order the
        // start-offset report prints.
        starts.sort(Comparator.comparing(StartTime::startTime));

        StartSheet sheet = new StartSheet(raceId, Instant.now(), target, earliest, starts);
        store.putStartSheet(sheet);

        // Persist the inputs on the race too, so reopening it shows what the
        // published sheet was actually computed from.
        store.putRace(new Race(race.id(), race.seriesId(), race.number(), race.name(),
            race.date(), earliest, target, race.courseLengthNm(), race.abandoned()));

        writeJson(resp, mapOf("ok", true, "raceId", raceId, "startSheet", sheet));
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
    private void handleCoursePlan(HttpServletRequest req, HttpServletResponse resp, String raceId)
        throws Exception
    {
        Race race = store.races().get(raceId);
        if (race == null)
        {
            resp.setStatus(404);
            writeJson(resp, mapOf("error", "no such race: " + raceId));
            return;
        }
        JsonNode body = MAPPER.readTree(req.getInputStream());
        JinxConfig.Algorithm alg = algorithmFor(race.seriesId());

        int duration = body.hasNonNull("targetElapsedMinutes")
            ? body.path("targetElapsedMinutes").asInt()
            : (race.targetElapsedMinutes() != null
                ? race.targetElapsedMinutes() : alg.idealRaceDuration());
        if (duration <= 0)
            duration = alg.idealRaceDuration();

        double slowestTcf = body.hasNonNull("slowestTcf")
            ? body.path("slowestTcf").asDouble()
            : slowestEntrantTcf(raceId);

        LocalTime earliest = race.earliestStart() != null
            ? race.earliestStart() : LocalTime.parse(alg.earliestStart());
        LocalTime sunset = sunsetFor(alg, race);

        CoursePlan plan = computeCoursePlan(alg.v0knots(), duration,
            alg.limitBySunset(), earliest, sunset, slowestTcf);

        writeJson(resp, mapOf(
            "raceId", raceId,
            "requestedDurationMinutes", duration,
            "effectiveDurationMinutes", plan.effectiveDurationMinutes(),
            "limitedBySunset", plan.limitedBySunset(),
            "sunsetLocal", sunset == null ? null
                : String.format("%02d:%02d", sunset.getHour(), sunset.getMinute()),
            "v0knots", alg.v0knots(),
            "slowestTcf", slowestTcf,
            "courseLengthNm", plan.courseLengthNm()));
    }

    private double slowestEntrantTcf(String raceId)
    {
        RaceEntrants e = store.entrants(raceId);
        if (e == null)
            return 1.0;
        return e.entrants().stream().mapToDouble(Entrant::tcf).min().orElse(1.0);
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
    record CoursePlan(int effectiveDurationMinutes, boolean limitedBySunset, double courseLengthNm)
    {
    }

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
    static CoursePlan computeCoursePlan(double v0Knots, int requestedDurationMinutes,
                                        boolean limitBySunset, LocalTime earliestStart,
                                        LocalTime sunset, double slowestTcf)
    {
        int effective = requestedDurationMinutes;
        boolean limited = false;
        if (limitBySunset && sunset != null && earliestStart != null)
        {
            long maxMinutes = java.time.Duration.between(earliestStart, sunset).toMinutes();
            if (effective > maxMinutes)
            {
                effective = (int)Math.max(0, maxMinutes);
                limited = true;
            }
        }
        double nm = slowestTcf * v0Knots * (effective / 60.0);
        return new CoursePlan(effective, limited, Math.round(nm * 10.0) / 10.0);
    }

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
        requireAdmin(resp);
        Race race = store.races().get(raceId);
        JsonNode body = MAPPER.readTree(req.getInputStream());
        JinxConfig.Algorithm alg = algorithmFor(race == null ? null : race.seriesId());

        int tTarget = body.path("targetElapsedMinutes").asInt(
            race != null && race.targetElapsedMinutes() != null
                ? race.targetElapsedMinutes() : alg.idealRaceDuration());

        JsonNode boatsNode = body.path("boats");
        if (!boatsNode.isArray() || boatsNode.isEmpty())
        {
            badRequest(resp, "boats array required");
            return;
        }

        List<Boat> boats = new ArrayList<>(boatsNode.size());
        Map<String, Result> results = new LinkedHashMap<>(boatsNode.size());
        for (JsonNode b : boatsNode)
        {
            String boatId = text(b, "boatId");
            if (isBlank(boatId))
                continue;
            double tcf = b.path("currentTcf").asDouble(1.0);
            boats.add(new Boat(boatId, "", "", null, null, null, tcf, false, true, null));

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
            LocalTime startT = LocalTime.MIDNIGHT;
            LocalTime finishT = null;
            if (status == FinishStatus.FIN && b.has("elapsedMinutes"))
                finishT = startT.plusSeconds(Math.round(b.path("elapsedMinutes").asDouble(0) * 60.0));

            Integer finishPosition = b.hasNonNull("finishPosition")
                ? b.path("finishPosition").asInt() : null;
            results.put(boatId, new Result(boatId, status, startT, finishT, null, finishPosition));
        }

        Race forEngine = new Race(raceId, race == null ? null : race.seriesId(), 0, "",
            null, null, tTarget, null, false);
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
        requireAdmin(resp);
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
            0.0, adjustments.stream().mapToDouble(Adjustment::penaltyMinutes).sum(),
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
                if (!e.scoresHandicap())
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
        requireAdmin(resp);
        boolean removed = store.deleteAdjustments(raceId);
        if (removed)
        {
            store.appendAudit(new AuditEntry(Instant.now(), raceId, "unlock",
                0.0, 0.0, List.of(), "adjustments discarded for reprocessing"));
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
        m.put("idealRaceDuration", a.idealRaceDuration());
        m.put("dnfAllowance", a.dnfAllowance());
        m.put("earliestStart", a.earliestStart());
        m.put("latitude", a.latitude());
        m.put("longitude", a.longitude());
        m.put("limitBySunset", a.limitBySunset());
        m.put("v0knots", a.v0knots());
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
        m.put("courseLengthNm", r.courseLengthNm());
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
     * Role gate. Currently always passes — see {@link #currentRole}. Kept at
     * every call site that will need it so that turning authentication on is a
     * change in one place rather than an audit of the whole servlet.
     */
    private void requireAdmin(HttpServletResponse resp) throws IOException
    {
        if (currentRole(null) != Role.ADMIN)
        {
            resp.setStatus(403);
            writeJson(resp, mapOf("error", "admin required"));
        }
    }

    /** Short, opaque, stable id. Prefixed so a store filename says what it is. */
    private static String mintId(String prefix)
    {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
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
