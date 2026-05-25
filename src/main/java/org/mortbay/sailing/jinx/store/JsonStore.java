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
import org.mortbay.sailing.jinx.model.AuditEntry;
import org.mortbay.sailing.jinx.model.Boat;
import org.mortbay.sailing.jinx.model.Race;
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
 *   boats.json                — Map&lt;boatId, Boat&gt;
 *   races.json                — Map&lt;raceId, Race&gt;
 *   results/{raceId}.json     — Map&lt;boatId, Result&gt; per race
 *   audit.json                — List&lt;AuditEntry&gt;, append-only
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
        this.auditFile = storeDir.resolve("audit.json");
    }

    /** Create directories if needed and load all entities into memory. */
    public synchronized void start() throws IOException
    {
        Files.createDirectories(storeDir);
        Files.createDirectories(resultsDir);

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
