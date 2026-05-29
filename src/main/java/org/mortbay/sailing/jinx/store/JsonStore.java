package org.mortbay.sailing.jinx.store;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.mortbay.sailing.jinx.config.JinxConfig;
import org.mortbay.sailing.jinx.model.Adjustment;
import org.mortbay.sailing.jinx.model.AuditEntry;
import org.mortbay.sailing.jinx.model.Boat;
import org.mortbay.sailing.jinx.model.Calibration;
import org.mortbay.sailing.jinx.model.Race;
import org.mortbay.sailing.jinx.model.RaceTimes;
import org.mortbay.sailing.jinx.model.Result;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * On-disk JSON persistence for sail-jinx state, modelled on the sailing-pf
 * {@code DataStore} pattern but kept much simpler: one file per logical entity,
 * no per-entity-instance files, full reload on start.
 *
 * <p>Layout under {@code <root>/store/}:
 * <pre>
 *   boats.json                  — Map&lt;boatId, Boat&gt;
 *   races.json                  — Map&lt;raceId, Race&gt;
 *   results/{raceId}.json       — Map&lt;boatId, Result&gt; per race
 *   race-times/{raceId}.json    — RaceTimes per race (RO-captured wall clock)
 *   series-config/{seriesId}.json — per-series Jinx algorithm settings (overrides config.yaml)
 *   pending-adjustments/{raceId}.json — Jinx-computed adjustments awaiting push to SailSys
 *   calibration.json            — Calibration of SailSys' TCF → start-offset conversion
 *   audit.json                  — List&lt;AuditEntry&gt;, append-only
 * </pre>
 *
 * <p>This is deliberately not a database. The dataset is small (one club, one
 * series, ~20 boats, 20 races/year) and human-readable JSON is the right
 * trade-off — easy to inspect, easy to back up, easy to hand-edit if something
 * goes wrong on race night.
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

    private final Path storeDir;
    private final Path boatsFile;
    private final Path racesFile;
    private final Path resultsDir;
    private final Path raceTimesDir;
    private final Path seriesConfigDir;
    private final Path pendingAdjustmentsDir;
    private final Path calibrationFile;
    private final Path auditFile;

    private Map<String, Boat> boats;
    private Map<String, Race> races;
    private List<AuditEntry> audit;

    public JsonStore(Path dataRoot)
    {
        this.storeDir = dataRoot.resolve("store");
        this.boatsFile = storeDir.resolve("boats.json");
        this.racesFile = storeDir.resolve("races.json");
        this.resultsDir = storeDir.resolve("results");
        this.raceTimesDir = storeDir.resolve("race-times");
        this.seriesConfigDir = storeDir.resolve("series-config");
        this.pendingAdjustmentsDir = storeDir.resolve("pending-adjustments");
        this.calibrationFile = storeDir.resolve("calibration.json");
        this.auditFile = storeDir.resolve("audit.json");
    }

    /** Create directories if needed and load all entities into memory. */
    public synchronized void start() throws IOException
    {
        Files.createDirectories(storeDir);
        Files.createDirectories(resultsDir);
        Files.createDirectories(raceTimesDir);
        Files.createDirectories(seriesConfigDir);
        Files.createDirectories(pendingAdjustmentsDir);

        boats = readMap(boatsFile, new TypeReference<>() { });
        races = readMap(racesFile, new TypeReference<>() { });
        audit = readList(auditFile, new TypeReference<>() { });

        LOG.info("JsonStore started: {} boats, {} races, {} audit entries",
            boats.size(), races.size(), audit.size());
    }

    private static <V> Map<String, V> readMap(Path file, TypeReference<Map<String, V>> type) throws IOException
    {
        if (!Files.exists(file))
            return new LinkedHashMap<>();
        return new LinkedHashMap<>(MAPPER.readValue(Files.readAllBytes(file), type));
    }

    private static <V> List<V> readList(Path file, TypeReference<List<V>> type) throws IOException
    {
        if (!Files.exists(file))
            return new ArrayList<>();
        return new ArrayList<>(MAPPER.readValue(Files.readAllBytes(file), type));
    }

    // --- Boats ---

    public synchronized Map<String, Boat> boats()
    {
        return Collections.unmodifiableMap(boats);
    }

    public synchronized void putBoat(Boat boat) throws IOException
    {
        boats.put(boat.id(), boat);
        MAPPER.writeValue(boatsFile.toFile(), boats);
    }

    /** Bulk replace — used after a fresh fetch of series entries from SailSys. */
    public synchronized void replaceBoats(Map<String, Boat> fresh) throws IOException
    {
        boats = new LinkedHashMap<>(fresh);
        MAPPER.writeValue(boatsFile.toFile(), boats);
    }

    // --- Races ---

    public synchronized Map<String, Race> races()
    {
        return Collections.unmodifiableMap(races);
    }

    public synchronized void putRace(Race race) throws IOException
    {
        races.put(race.id(), race);
        MAPPER.writeValue(racesFile.toFile(), races);
    }

    // --- Results (per race) ---

    public synchronized Map<String, Result> results(String raceId) throws IOException
    {
        Path file = resultsDir.resolve(raceId + ".json");
        if (!Files.exists(file))
            return Map.of();
        return Collections.unmodifiableMap(
            MAPPER.readValue(Files.readAllBytes(file), new TypeReference<Map<String, Result>>() { }));
    }

    public synchronized void putResults(String raceId, Map<String, Result> results) throws IOException
    {
        Path file = resultsDir.resolve(raceId + ".json");
        MAPPER.writeValue(file.toFile(), results);
    }

    // --- Race times (RO-captured wall clock, per race) ---

    /** Returns the saved race times for the given race, or {@code null} if none have been saved. */
    public synchronized RaceTimes raceTimes(String raceId) throws IOException
    {
        Path file = raceTimesDir.resolve(raceId + ".json");
        if (!Files.exists(file))
            return null;
        return MAPPER.readValue(Files.readAllBytes(file), RaceTimes.class);
    }

    public synchronized void putRaceTimes(String raceId, RaceTimes times) throws IOException
    {
        Path file = raceTimesDir.resolve(raceId + ".json");
        MAPPER.writeValue(file.toFile(), times);
    }

    // --- Series config (per-series Jinx algorithm overrides) ---

    /**
     * Returns the saved per-series algorithm config, or {@code null} if the
     * series has never been configured. Callers should fall back to
     * {@code JinxConfig.algorithm()} (the yaml defaults) in that case.
     */
    public synchronized JinxConfig.Algorithm seriesConfig(String seriesId) throws IOException
    {
        Path file = seriesConfigDir.resolve(seriesId + ".json");
        if (!Files.exists(file))
            return null;
        return MAPPER.readValue(Files.readAllBytes(file), JinxConfig.Algorithm.class);
    }

    public synchronized void putSeriesConfig(String seriesId, JinxConfig.Algorithm cfg) throws IOException
    {
        Path file = seriesConfigDir.resolve(seriesId + ".json");
        MAPPER.writeValue(file.toFile(), cfg);
    }

    // --- Pending adjustments (Jinx-computed, awaiting push to SailSys) ---

    /**
     * Returns the locally-saved adjustments for a race, or an empty list when none
     * have been saved. Populated when the admin presses SAVE on the Process
     * Handicaps panel; cleared once the adjustments are pushed to SailSys.
     */
    public synchronized List<Adjustment> pendingAdjustments(String raceId) throws IOException
    {
        Path file = pendingAdjustmentsDir.resolve(raceId + ".json");
        if (!Files.exists(file))
            return List.of();
        return MAPPER.readValue(Files.readAllBytes(file),
            new TypeReference<List<Adjustment>>() { });
    }

    public synchronized void putPendingAdjustments(String raceId, List<Adjustment> adjustments)
        throws IOException
    {
        Path file = pendingAdjustmentsDir.resolve(raceId + ".json");
        MAPPER.writeValue(file.toFile(), adjustments);
    }

    // --- Calibration (single, global — one SailSys instance per club) ---

    /**
     * Returns the saved calibration, or {@code null} if the SailSys conversion
     * has never been probed. The {@link org.mortbay.sailing.jinx.pursuit.PursuitHandicapEngine}
     * requires a calibration to convert {@code netAdjustmentMinutes} into a
     * new TCF; the API layer surfaces a clear error when {@code calibration()}
     * is {@code null} and the user attempts to run Process Handicaps.
     */
    public synchronized Calibration calibration() throws IOException
    {
        if (!Files.exists(calibrationFile))
            return null;
        return MAPPER.readValue(Files.readAllBytes(calibrationFile), Calibration.class);
    }

    public synchronized void putCalibration(Calibration cal) throws IOException
    {
        MAPPER.writeValue(calibrationFile.toFile(), cal);
    }

    // --- Audit ---

    public synchronized List<AuditEntry> audit()
    {
        return Collections.unmodifiableList(audit);
    }

    public synchronized void appendAudit(AuditEntry entry) throws IOException
    {
        audit.add(entry);
        MAPPER.writeValue(auditFile.toFile(), audit);
    }
}
