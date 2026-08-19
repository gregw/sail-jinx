package org.mortbay.sailing.jinx.identity;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Hand-maintained judgements about designs, loaded from {@code data/config/design.yaml}
 * and seeded from the sailing-pf project.
 *
 * <p>Design names arrive from whoever typed them and are not a controlled vocabulary.
 * Three kinds of correction are needed, and none can be derived:
 *
 * <ul>
 *   <li><b>ignored</b> — labels that are not designs at all: {@code yacht}, {@code sloop},
 *       {@code monohull}, {@code custom}, or junk. Left alone they fragment a boat's
 *       history: the same hull appears once design-less and again as {@code …-yacht}, and
 *       neither record sees the other. An ignored design is cleared, so the boat matches
 *       as design-less.</li>
 *   <li><b>excluded</b> — real designs that are out of scope for this application:
 *       dinghies, boards, catamarans. sail-jinx runs a keelboat pursuit series.</li>
 *   <li><b>boatDesignOverrides</b> — this specific hull really is that design, whatever
 *       the source said, optionally only between two dates (a boat can be refitted).
 *       Keyed by <b>post-alias</b> sail and name.</li>
 * </ul>
 *
 * <p>Plus {@code noSpinnaker}: designs that physically cannot fly one.
 *
 * <p>Read-only. Unlike {@link Aliases}, nothing here is learned automatically — deciding
 * that "custom" is not a design, or that a particular boat was re-rigged, is a judgement
 * a person makes and writes down.
 */
public class DesignCatalogue
{
    private static final Logger LOG = LoggerFactory.getLogger(DesignCatalogue.class);
    private static final String FILENAME = "design.yaml";

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory())
        .registerModule(new JavaTimeModule());

    private final Set<String> excludedIds;
    private final Set<String> ignoredIds;
    private final Set<String> noSpinnakerIds;
    /** "normSail|normName" → overrides, possibly date-ranged */
    private final Map<String, List<Override>> overridesByBoat;
    /** design id → canonical name supplied by an override block */
    private final Map<String, String> overrideDesignNames;

    private DesignCatalogue(Yaml yaml)
    {
        excludedIds = normaliseAll(yaml == null ? null : yaml.excluded);
        ignoredIds = normaliseAll(yaml == null ? null : yaml.ignored);
        noSpinnakerIds = normaliseAll(yaml == null ? null : yaml.noSpinnaker);

        Map<String, List<Override>> byBoat = new HashMap<>();
        Map<String, String> designNames = new HashMap<>();
        if (yaml != null && yaml.boatDesignOverrides != null)
        {
            for (DesignOverride block : yaml.boatDesignOverrides)
            {
                if (block == null || block.designId == null || block.boats == null)
                    continue;
                String designId = IdGenerator.normaliseDesignName(block.designId);
                String canonical = (block.canonicalName != null && !block.canonicalName.isBlank())
                    ? block.canonicalName.trim() : block.designId;
                designNames.put(designId, canonical);
                for (BoatOverride boat : block.boats)
                {
                    if (boat == null || boat.sailNumber == null || boat.name == null)
                        continue;
                    String key = overrideKey(
                        IdGenerator.normaliseSailNumber(boat.sailNumber),
                        IdGenerator.normaliseName(boat.name));
                    byBoat.computeIfAbsent(key, k -> new ArrayList<>())
                        .add(new Override(designId, canonical, boat.from, boat.until));
                }
            }
        }
        overridesByBoat = Collections.unmodifiableMap(byBoat);
        overrideDesignNames = Collections.unmodifiableMap(designNames);

        LOG.info("Design catalogue: {} ignored, {} excluded, {} no-spinnaker, {} boat override(s)",
            ignoredIds.size(), excludedIds.size(), noSpinnakerIds.size(),
            byBoat.values().stream().mapToInt(List::size).sum());
    }

    /** Load from {@code configDir/design.yaml}; an absent or broken file yields an empty catalogue. */
    public static DesignCatalogue load(Path configDir)
    {
        Path file = configDir.resolve(FILENAME);
        if (!Files.exists(file))
        {
            LOG.info("No {} — every design name will be taken at face value", file.toAbsolutePath());
            return new DesignCatalogue(null);
        }
        try
        {
            return new DesignCatalogue(YAML.readValue(file.toFile(), Yaml.class));
        }
        catch (Exception e)
        {
            LOG.error("Could not read {} — continuing with an empty catalogue: {}", file, e.toString());
            return new DesignCatalogue(null);
        }
    }

    /** True when this "design" is really a generic label and should be discarded. */
    public boolean isIgnored(String designId)
    {
        return designId != null && ignoredIds.contains(designId);
    }

    /** True when this design is out of scope for a keelboat pursuit series. */
    public boolean isExcluded(String designId)
    {
        return designId != null && excludedIds.contains(designId);
    }

    /** True when this design physically cannot fly a spinnaker. */
    public boolean isNoSpinnaker(String designId)
    {
        return designId != null && noSpinnakerIds.contains(designId);
    }

    /**
     * The design this specific boat really is, or null when there is no override.
     * Keyed by <b>post-alias</b> sail and name — an override written against an alias
     * spelling will never fire.
     *
     * @param date the race date, or null to match only undated overrides
     */
    public String resolveOverride(String normSail, String normName, LocalDate date)
    {
        List<Override> candidates = overridesByBoat.get(overrideKey(normSail, normName));
        if (candidates == null)
            return null;
        for (Override o : candidates)
        {
            if (o.isActiveOn(date))
                return o.designId();
        }
        return null;
    }

    /** Canonical display name contributed by an override block, or null. */
    public String overrideDesignName(String designId)
    {
        return overrideDesignNames.get(designId);
    }

    private static String overrideKey(String normSail, String normName)
    {
        return normSail + "|" + normName;
    }

    private static Set<String> normaliseAll(List<String> names)
    {
        if (names == null)
            return Set.of();
        Set<String> out = new HashSet<>();
        for (String name : names)
        {
            if (name != null && !name.isBlank())
                out.add(IdGenerator.normaliseDesignName(name));
        }
        return Collections.unmodifiableSet(out);
    }

    /** One override, optionally limited to a date range. */
    private record Override(String designId, String canonicalName, LocalDate from, LocalDate until)
    {
        boolean isActiveOn(LocalDate date)
        {
            // An undated query only matches an undated override: without a date we cannot
            // honestly answer "which design was it at the time".
            if (date == null)
                return from == null && until == null;
            if (from != null && date.isBefore(from))
                return false;
            return until == null || !date.isAfter(until);
        }
    }

    // --- YAML binding --------------------------------------------------------

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class Yaml
    {
        public List<String> excluded;
        public List<String> ignored;
        public List<String> noSpinnaker;
        public List<DesignOverride> boatDesignOverrides;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class DesignOverride
    {
        public String designId;
        public String canonicalName;
        public List<BoatOverride> boats;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class BoatOverride
    {
        public String sailNumber;
        public String name;
        public LocalDate from;
        public LocalDate until;
    }
}
