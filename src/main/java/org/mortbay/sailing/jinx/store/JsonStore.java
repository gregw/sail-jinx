package org.mortbay.sailing.jinx.store;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.mortbay.sailing.jinx.config.JinxConfig;
import org.mortbay.sailing.jinx.identity.IdGenerator;
import org.mortbay.sailing.jinx.model.Adjustment;
import org.mortbay.sailing.jinx.model.AuditEntry;
import org.mortbay.sailing.jinx.model.Boat;
import org.mortbay.sailing.jinx.model.Design;
import org.mortbay.sailing.jinx.model.Entrant;
import org.mortbay.sailing.jinx.model.Race;
import org.mortbay.sailing.jinx.model.RaceEntrants;
import org.mortbay.sailing.jinx.model.RaceTimes;
import org.mortbay.sailing.jinx.model.Series;
import org.mortbay.sailing.jinx.model.StartSheet;
import org.mortbay.sailing.jinx.model.StartTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * On-disk JSON persistence for sail-jinx. One file per logical entity, full
 * reload on start, no database.
 *
 * <p>Layout under {@code <root>/store/}:
 * <pre>
 *   boats.json                    — Map&lt;boatId, Boat&gt;: the fleet register
 *   designs.json                  — Map&lt;designId, Design&gt;: hull types, learned from boat entry
 *   series.json                   — Map&lt;seriesId, Series&gt;
 *   races.json                    — Map&lt;raceId, Race&gt;
 *   entrants/{raceId}.json        — RaceEntrants: who is in this race, at what TCF
 *   start-sheet/{raceId}.json     — computed pursuit start times
 *   race-times/{raceId}.json      — RO-captured came / actual start / finish
 *   series-config/{seriesId}.json — per-series algorithm overrides
 *   adjustments/{raceId}.json     — saved handicap adjustments (also the race lock)
 *   audit.json                    — List&lt;AuditEntry&gt;, append-only
 *   journal/{yyyy-MM}.jsonl       — append-only record of every mutation
 * </pre>
 *
 * <p>The dataset is small — one club, ~40 boats, ~20 races a year — and
 * human-readable JSON is the right trade-off: easy to inspect, easy to back up,
 * easy to hand-edit when something goes wrong on race night.
 *
 * <p><b>This is the only copy.</b> Since v2 there is no SailSys behind it to
 * re-fetch from, so three things that used to be optional are not:
 * <ul>
 *   <li><b>Atomic writes.</b> Every file is written to a sibling {@code .tmp}
 *       and then moved into place, so a crash or a full disk can never leave a
 *       half-written file where a good one used to be.</li>
 *   <li><b>A journal.</b> Every mutation also appends one self-describing JSON
 *       line to {@code journal/}. It is the recovery path for the cases atomic
 *       writes can't cover — a crash between two related writes, or a file
 *       somebody edited badly by hand.</li>
 *   <li><b>Defensive loading.</b> A corrupt file is reported through
 *       {@link #loadErrors()} and skipped, never allowed to stop the server
 *       from starting. Losing one race's times on a Thursday evening is
 *       recoverable; not being able to start the app is not.</li>
 * </ul>
 */
public class JsonStore
{
    private static final Logger LOG = LoggerFactory.getLogger(JsonStore.class);

    private static final JsonMapper MAPPER = JsonMapper.builder()
        .addModule(new JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        .enable(SerializationFeature.INDENT_OUTPUT)
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .build();

    /** Journal lines are one-per-line, so they must not be pretty-printed. */
    private static final JsonMapper JOURNAL_MAPPER = JsonMapper.builder()
        .addModule(new JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        .build();

    private static final DateTimeFormatter JOURNAL_MONTH =
        DateTimeFormatter.ofPattern("yyyy-MM");

    private final Path storeDir;
    private final Path boatsFile;
    private final Path designsFile;
    private final Path seriesFile;
    private final Path racesFile;
    private final Path entrantsDir;
    private final Path startSheetDir;
    private final Path raceTimesDir;
    private final Path seriesConfigDir;
    private final Path adjustmentsDir;
    private final Path auditFile;
    private final Path journalDir;

    private Map<String, Boat> boats;
    private Map<String, Design> designs;
    private Map<String, Series> series;
    private Map<String, Race> races;
    private List<AuditEntry> audit;
    private final List<String> loadErrors = new ArrayList<>();

    public JsonStore(Path dataRoot)
    {
        this.storeDir = dataRoot.resolve("store");
        this.boatsFile = storeDir.resolve("boats.json");
        this.designsFile = storeDir.resolve("designs.json");
        this.seriesFile = storeDir.resolve("series.json");
        this.racesFile = storeDir.resolve("races.json");
        this.entrantsDir = storeDir.resolve("entrants");
        this.startSheetDir = storeDir.resolve("start-sheet");
        this.raceTimesDir = storeDir.resolve("race-times");
        this.seriesConfigDir = storeDir.resolve("series-config");
        this.adjustmentsDir = storeDir.resolve("adjustments");
        this.auditFile = storeDir.resolve("audit.json");
        this.journalDir = storeDir.resolve("journal");
    }

    /** Create directories if needed and load the in-memory entities. */
    public synchronized void start() throws IOException
    {
        for (Path dir : List.of(storeDir, entrantsDir, startSheetDir,
            raceTimesDir, seriesConfigDir, adjustmentsDir, journalDir))
        {
            Files.createDirectories(dir);
        }

        boats = readMap(boatsFile, new TypeReference<>() { });
        designs = readMap(designsFile, new TypeReference<>() { });
        series = readMap(seriesFile, new TypeReference<>() { });
        races = readMap(racesFile, new TypeReference<>() { });
        audit = readList(auditFile, new TypeReference<>() { });

        LOG.info("JsonStore started: {} boats, {} series, {} races, {} audit entries{}",
            boats.size(), series.size(), races.size(), audit.size(),
            loadErrors.isEmpty() ? "" : (" — " + loadErrors.size() + " FILE(S) UNREADABLE"));
        for (String e : loadErrors)
            LOG.error("Store load error: {}", e);
    }

    /**
     * Files that could not be parsed during {@link #start()} or a subsequent
     * read, as human-readable messages. Empty in the normal case. The UI
     * surfaces these rather than letting a bad file fail silently.
     */
    public synchronized List<String> loadErrors()
    {
        return List.copyOf(loadErrors);
    }

    // --- Fleet register ------------------------------------------------------

    public synchronized Map<String, Boat> boats()
    {
        return Collections.unmodifiableMap(boats);
    }

    public synchronized void putBoat(Boat boat) throws IOException
    {
        boats.put(boat.id(), boat);
        write(boatsFile, boats);
        journal("boats", boat.id(), boat);
    }

    // --- Designs -------------------------------------------------------------

    public synchronized Map<String, Design> designs()
    {
        return Collections.unmodifiableMap(designs);
    }

    public synchronized void putDesign(Design design) throws IOException
    {
        designs.put(design.id(), design);
        write(designsFile, designs);
        journal("designs", design.id(), design);
    }

    // --- Boat identity changes -----------------------------------------------

    /**
     * Move a boat to a new id, rewriting every reference to it.
     *
     * <p>This happens when a boat's identity is corrected — most often when a design-less
     * boat's design becomes known, since the design is part of the id. The alternative
     * would be a second record, splitting the boat's handicap history in half.
     *
     * <p>Every place a boat id appears has to move with it, and missing one silently
     * orphans a race:
     * <ul>
     *   <li>the register itself;</li>
     *   <li>each race's entrant list;</li>
     *   <li>each race's captured times — the per-boat map keys, the working order, and
     *       the duty boat;</li>
     *   <li>each race's published start sheet;</li>
     *   <li>saved handicap adjustments.</li>
     * </ul>
     *
     * <p>If a boat already occupies the target id the two are the same hull found twice,
     * so the moving record is dropped and its references repointed at the survivor.
     *
     * @return the number of files rewritten
     */
    public synchronized int rewriteBoatId(String oldId, String newId) throws IOException
    {
        if (oldId == null || newId == null || oldId.equals(newId))
            return 0;

        int touched = 0;
        Boat moving = boats.remove(oldId);
        if (moving != null)
        {
            // Keep the incumbent when the target is taken: it may already carry edits.
            if (!boats.containsKey(newId))
                boats.put(newId, withId(moving, newId));
            write(boatsFile, boats);
            journal("boats", newId, boats.get(newId));
            touched++;
        }

        for (String raceId : idsIn(entrantsDir))
        {
            RaceEntrants entrants = entrants(raceId);
            if (entrants == null)
                continue;
            boolean changed = false;
            List<Entrant> updated = new ArrayList<>(entrants.entrants().size());
            for (Entrant e : entrants.entrants())
            {
                if (oldId.equals(e.boatId()))
                {
                    updated.add(new Entrant(newId, e.sailNumber(), e.name(), e.division(),
                        e.designId(), e.spinnaker(), e.tcf(), e.entryType()));
                    changed = true;
                }
                else
                {
                    updated.add(e);
                }
            }
            if (changed)
            {
                putEntrants(new RaceEntrants(entrants.raceId(), entrants.savedAt(),
                    entrants.tcfSource(), entrants.sourceRaceId(), entrants.sourceRaceNumber(),
                    updated));
                touched++;
            }
        }

        for (String raceId : idsIn(raceTimesDir))
        {
            RaceTimes times = raceTimes(raceId);
            if (times == null || !referencesBoat(times, oldId))
                continue;
            Map<String, RaceTimes.BoatTimes> perBoat = new LinkedHashMap<>();
            for (Map.Entry<String, RaceTimes.BoatTimes> e : times.times().entrySet())
                perBoat.put(oldId.equals(e.getKey()) ? newId : e.getKey(), e.getValue());
            List<String> order = times.boatOrder().stream()
                .map(id -> oldId.equals(id) ? newId : id).toList();
            String duty = oldId.equals(times.dutyBoatId()) ? newId : times.dutyBoatId();
            putRaceTimes(raceId, new RaceTimes(raceId, order, duty, perBoat));
            touched++;
        }

        for (String raceId : idsIn(startSheetDir))
        {
            StartSheet sheet = startSheet(raceId);
            if (sheet == null)
                continue;
            boolean changed = sheet.starts().stream().anyMatch(st -> oldId.equals(st.boatId()));
            if (!changed)
                continue;
            List<StartTime> starts = sheet.starts().stream()
                .map(st -> oldId.equals(st.boatId())
                    ? new StartTime(newId, st.tcf(), st.expectedElapsedMinutes(), st.startTime())
                    : st)
                .toList();
            putStartSheet(new StartSheet(raceId, sheet.computedAt(), sheet.targetElapsedMinutes(),
                sheet.earliestStart(), starts));
            touched++;
        }

        for (String raceId : idsIn(adjustmentsDir))
        {
            List<Adjustment> saved = adjustments(raceId);
            if (saved.stream().noneMatch(a -> oldId.equals(a.boatId())))
                continue;
            List<Adjustment> updated = saved.stream()
                .map(a -> oldId.equals(a.boatId())
                    ? new Adjustment(newId, a.finishPosition(), a.penaltyMinutes(),
                        a.rewardMinutes(), a.netAdjustmentMinutes(), a.oldTcf(), a.newTcf())
                    : a)
                .toList();
            putAdjustments(raceId, updated);
            touched++;
        }

        LOG.info("Rewrote boat id {} -> {} across {} file(s)", oldId, newId, touched);
        return touched;
    }

    private static Boat withId(Boat b, String id)
    {
        return new Boat(id, b.sailNumber(), b.name(), b.designId(),
            b.casual(), b.active(), b.notes());
    }

    private static boolean referencesBoat(RaceTimes times, String boatId)
    {
        return times.times().containsKey(boatId)
            || times.boatOrder().contains(boatId)
            || boatId.equals(times.dutyBoatId());
    }

    /** The keys of the per-entity files in a directory, i.e. the filenames without .json. */
    private List<String> idsIn(Path dir)
    {
        if (!Files.isDirectory(dir))
            return List.of();
        try (var files = Files.list(dir))
        {
            return files
                .map(p -> p.getFileName().toString())
                .filter(n -> n.endsWith(".json"))
                .map(n -> n.substring(0, n.length() - 5))
                .sorted()
                .toList();
        }
        catch (IOException e)
        {
            LOG.warn("Could not list {}: {}", dir, e.toString());
            return List.of();
        }
    }

    // --- Series --------------------------------------------------------------

    public synchronized Map<String, Series> series()
    {
        return Collections.unmodifiableMap(series);
    }

    public synchronized void putSeries(Series s) throws IOException
    {
        series.put(s.id(), s);
        write(seriesFile, series);
        journal("series", s.id(), s);
    }

    /**
     * Deletes a series, every race in it, and every file hanging off those races.
     *
     * <p>A season is not just a row in {@code series.json}. Each of its races owns an
     * entrant list, a start sheet, captured times and possibly saved adjustments, each in
     * its own file keyed by race id — and the series owns a config override. Leaving any
     * of them behind would litter the store with data no page can reach: race ids embed
     * the date and a sequence number, so a future race will never mint the same one and
     * adopt them.
     *
     * <p>Irreversible, and meant to be: the journal keeps a record of what was removed,
     * but nothing reads it back. The caller is responsible for asking first.
     *
     * @return the number of races removed, which is 0 for an unknown series
     */
    public synchronized int deleteSeries(String seriesId) throws IOException
    {
        if (seriesId == null || !series.containsKey(seriesId))
            return 0;

        List<Race> doomed = racesInSeries(seriesId);
        for (Race race : doomed)
        {
            String raceId = race.id();
            deleteIfPresent(entrantsDir, raceId, "entrants");
            deleteIfPresent(startSheetDir, raceId, "start-sheet");
            deleteIfPresent(raceTimesDir, raceId, "race-times");
            deleteIfPresent(adjustmentsDir, raceId, "adjustments");
            races.remove(raceId);
            journalDelete("races", raceId);
        }
        if (!doomed.isEmpty())
            write(racesFile, races);

        deleteIfPresent(seriesConfigDir, fileKey(seriesId), "series-config");
        series.remove(seriesId);
        write(seriesFile, series);
        journalDelete("series", seriesId);

        LOG.info("Deleted series {} and {} race(s)", seriesId, doomed.size());
        return doomed.size();
    }

    /** Removes one keyed file if it is there, journalling the removal. */
    private void deleteIfPresent(Path dir, String key, String entity) throws IOException
    {
        if (Files.deleteIfExists(dir.resolve(key + ".json")))
            journalDelete(entity, key);
    }

    // --- Races ---------------------------------------------------------------

    public synchronized Map<String, Race> races()
    {
        return Collections.unmodifiableMap(races);
    }

    public synchronized void putRace(Race race) throws IOException
    {
        races.put(race.id(), race);
        write(racesFile, races);
        journal("races", race.id(), race);
    }

    /** Races in the given series, ordered by race number. */
    public synchronized List<Race> racesInSeries(String seriesId)
    {
        return races.values().stream()
            .filter(r -> r.seriesId() != null && r.seriesId().equals(seriesId))
            .sorted(Comparator.comparingInt(Race::number))
            .toList();
    }

    /**
     * The race after the given one in its series, by race number. This is what
     * Save Handicaps needs in order to write the next race's entrant TCFs;
     * SailSys used to supply it as {@code nextRaceId}. Empty for the last race
     * in a series, or an unknown race.
     */
    public synchronized Optional<Race> nextRaceInSeries(String raceId)
    {
        Race race = races.get(raceId);
        if (race == null)
            return Optional.empty();
        return racesInSeries(race.seriesId()).stream()
            .filter(r -> r.number() > race.number())
            .findFirst();
    }

    // --- Race entrants -------------------------------------------------------

    /** The race's entrants, or {@code null} when none have been set up. */
    public synchronized RaceEntrants entrants(String raceId)
    {
        return read(entrantsDir.resolve(raceId + ".json"), RaceEntrants.class);
    }

    public synchronized void putEntrants(RaceEntrants entrants) throws IOException
    {
        write(entrantsDir.resolve(entrants.raceId() + ".json"), entrants);
        journal("entrants", entrants.raceId(), entrants);
    }

    // --- Start sheet ---------------------------------------------------------

    /** The computed start sheet, or {@code null} when start times haven't been computed. */
    public synchronized StartSheet startSheet(String raceId)
    {
        return read(startSheetDir.resolve(raceId + ".json"), StartSheet.class);
    }

    public synchronized void putStartSheet(StartSheet sheet) throws IOException
    {
        write(startSheetDir.resolve(sheet.raceId() + ".json"), sheet);
        journal("start-sheet", sheet.raceId(), sheet);
    }

    // --- Race times ----------------------------------------------------------

    /** The RO-captured times, or {@code null} when nothing has been saved. */
    public synchronized RaceTimes raceTimes(String raceId)
    {
        return read(raceTimesDir.resolve(raceId + ".json"), RaceTimes.class);
    }

    public synchronized void putRaceTimes(String raceId, RaceTimes times) throws IOException
    {
        write(raceTimesDir.resolve(raceId + ".json"), times);
        journal("race-times", raceId, times);
    }

    // --- Per-series algorithm config -----------------------------------------

    /**
     * The saved per-series algorithm config, or {@code null} when the series
     * has never been configured — callers fall back to
     * {@link JinxConfig#algorithm()}.
     */
    public synchronized JinxConfig.Algorithm seriesConfig(String seriesId)
    {
        return read(seriesConfigDir.resolve(fileKey(seriesId) + ".json"), JinxConfig.Algorithm.class);
    }

    public synchronized void putSeriesConfig(String seriesId, JinxConfig.Algorithm cfg)
        throws IOException
    {
        write(seriesConfigDir.resolve(fileKey(seriesId) + ".json"), cfg);
        journal("series-config", seriesId, cfg);
    }

    /**
     * Filename for an entity key. Series ids are club-scoped and carry a {@code /}
     * ({@code myc.org.au/twilight}), which would otherwise be read as a directory.
     */
    private static String fileKey(String id)
    {
        return IdGenerator.sanitizeIdForFilesystem(id);
    }

    /** Inverse of {@link #fileKey} — recovers the series id from a filename. */
    private static String unsanitize(String fileKey)
    {
        return fileKey == null ? "" : fileKey.replace("--", "/");
    }

    // --- Handicap adjustments ------------------------------------------------

    /**
     * The saved handicap adjustments for a race, or an empty list when the race
     * has not been processed. A non-empty list is also what locks the race:
     * see {@link Race}.
     */
    public synchronized List<Adjustment> adjustments(String raceId)
    {
        List<Adjustment> read = readList(adjustmentsDir.resolve(raceId + ".json"),
            new TypeReference<>() { });
        return Collections.unmodifiableList(read);
    }

    public synchronized void putAdjustments(String raceId, List<Adjustment> adjustments)
        throws IOException
    {
        write(adjustmentsDir.resolve(raceId + ".json"), adjustments);
        journal("adjustments", raceId, adjustments);
    }

    /**
     * Drops a race's saved adjustments — the Unlock action. Returns true when
     * something was actually removed.
     */
    public synchronized boolean deleteAdjustments(String raceId) throws IOException
    {
        boolean removed = Files.deleteIfExists(adjustmentsDir.resolve(raceId + ".json"));
        if (removed)
            journalDelete("adjustments", raceId);
        return removed;
    }

    // --- Audit ---------------------------------------------------------------

    public synchronized List<AuditEntry> audit()
    {
        return Collections.unmodifiableList(audit);
    }

    public synchronized void appendAudit(AuditEntry entry) throws IOException
    {
        audit.add(entry);
        write(auditFile, audit);
        journal("audit", entry.raceId(), entry);
    }

    // --- I/O internals -------------------------------------------------------

    /**
     * Write JSON to {@code file} atomically: serialise to a sibling
     * {@code .tmp}, force it to disk, then move it into place. The move is the
     * only moment the visible file changes, so a reader either sees the
     * complete old file or the complete new one — never a truncated mixture.
     *
     * <p>The temp file is a sibling rather than in the system temp directory so
     * the move stays within one filesystem, where it is atomic.
     *
     * <p>The parent directory is re-created on every write rather than only at
     * startup. It costs a stat and it means a store directory that vanishes
     * underneath a running server — a stray {@code rm}, an unmounted share, a
     * backup script that moved rather than copied — heals on the next save
     * instead of failing every write until someone restarts. On a system that
     * holds the only copy of the season, "keep going" beats "fail loudly".
     */
    private static void write(Path file, Object value) throws IOException
    {
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try
        {
            Files.createDirectories(file.getParent());
            byte[] bytes = MAPPER.writeValueAsBytes(value);
            Files.write(tmp, bytes,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE, StandardOpenOption.SYNC);
            Files.move(tmp, file,
                StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        }
        catch (IOException e)
        {
            Files.deleteIfExists(tmp);
            throw e;
        }
    }

    /**
     * Read one JSON file, or {@code null} when it doesn't exist. A parse
     * failure is recorded in {@link #loadErrors()} and returns {@code null} —
     * the caller sees "not saved yet", which every caller already handles,
     * rather than an exception that would take a page or the whole server down.
     */
    private <T> T read(Path file, Class<T> type)
    {
        if (!Files.exists(file))
            return null;
        try
        {
            return MAPPER.readValue(Files.readAllBytes(file), type);
        }
        catch (IOException e)
        {
            recordLoadError(file, e);
            return null;
        }
    }

    private <V> Map<String, V> readMap(Path file, TypeReference<Map<String, V>> type)
    {
        if (!Files.exists(file))
            return new LinkedHashMap<>();
        try
        {
            return new LinkedHashMap<>(MAPPER.readValue(Files.readAllBytes(file), type));
        }
        catch (IOException e)
        {
            recordLoadError(file, e);
            return new LinkedHashMap<>();
        }
    }

    private <V> List<V> readList(Path file, TypeReference<List<V>> type)
    {
        if (!Files.exists(file))
            return new ArrayList<>();
        try
        {
            return new ArrayList<>(MAPPER.readValue(Files.readAllBytes(file), type));
        }
        catch (IOException e)
        {
            recordLoadError(file, e);
            return new ArrayList<>();
        }
    }

    private void recordLoadError(Path file, Exception e)
    {
        String message = file.getFileName() + ": " + e.getMessage();
        loadErrors.add(message);
        LOG.error("Could not read {} — skipping it. Fix the file or restore it from "
            + "the journal; the data it held is NOT lost, but it is not loaded.", file, e);
    }

    /**
     * Append one line to this month's journal describing a mutation. Best
     * effort: a journal failure must never fail the write it is recording, or
     * the safety net would become the thing that breaks race night.
     */
    private void journal(String entity, String key, Object payload)
    {
        appendJournalLine(new JournalLine(Instant.now(), entity, key, false, payload));
    }

    private void journalDelete(String entity, String key)
    {
        appendJournalLine(new JournalLine(Instant.now(), entity, key, true, null));
    }

    private void appendJournalLine(JournalLine line)
    {
        Path file = journalDir.resolve(LocalDate.now().format(JOURNAL_MONTH) + ".jsonl");
        try
        {
            Files.createDirectories(journalDir);
            String json = JOURNAL_MAPPER.writeValueAsString(line) + "\n";
            Files.writeString(file, json, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }
        catch (IOException | UncheckedIOException e)
        {
            LOG.warn("Could not append to the journal ({}): {}", file, e.toString());
        }
    }

    /**
     * One journalled mutation. {@code deleted} distinguishes a removal from a
     * write of an empty value.
     */
    private record JournalLine(
        Instant ts,
        String entity,
        String key,
        boolean deleted,
        Object payload)
    {
    }
}
