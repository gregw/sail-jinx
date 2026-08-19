package org.mortbay.sailing.jinx.identity;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Known equivalences between the many ways one boat gets written down, loaded from
 * {@code data/config/aliases.yaml}.
 *
 * <p>Boats acquire sponsor prefixes, drop them again, change sail numbers, and get typed
 * differently by whoever is holding the clipboard. Normalisation ({@link IdGenerator})
 * handles the mechanical variation — case, punctuation, division suffixes. This class
 * handles the rest, which cannot be derived and has to be recorded: that
 * {@code "Andoo Comanche"} and {@code "Comanche"} are one boat, or that sail
 * {@code RF177} and {@code MYC10} are the same hull.
 *
 * <p>One equivalence <em>is</em> derived rather than listed: an Australian country or
 * fleet prefix and any leading zeros are stripped during matching, so {@code AUS01234},
 * {@code AUS1234} and {@code 1234} all match with no YAML entry. See {@link #stripPrefix}.
 *
 * <p>The file is shared in shape — and seeded from — the sailing-pf project, so an
 * equivalence discovered in one is usable in the other.
 *
 * <p><b>Writes are immediate.</b> When the registry learns an alias it is persisted at
 * once rather than accumulated in memory, because the thing being protected against is
 * the process not getting a clean shutdown.
 */
public class Aliases
{
    private static final Logger LOG = LoggerFactory.getLogger(Aliases.class);
    private static final String FILENAME = "aliases.yaml";

    private static final ObjectMapper YAML = new ObjectMapper(
        new YAMLFactory().disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER));

    /**
     * Australian country and fleet sail-number prefixes. {@code "JAUS"} is listed before
     * {@code "AUS"} so {@code JAUS103} strips to {@code 103}, not {@code US103}.
     */
    private static final List<String> AUS_PREFIXES = List.of("JAUS", "EAUS", "VAUS", "SAUS", "AUS");

    private final Path file;

    /** normalised alias name → canonical design id */
    private Map<String, String> name2designId = Map.of();
    /** canonical design id → display name */
    private Map<String, String> designId2Name = Map.of();
    /** alias sail number → boats that claim it */
    private Map<String, List<Indexed>> sailNo2Aliases = Map.of();
    /** alias name → boats that claim it */
    private Map<String, List<Indexed>> name2Aliases = Map.of();
    /** canonical "normSail-normName" → entry */
    private Map<String, BoatEntry> canonicalIndex = Map.of();

    private Aliases(Path file)
    {
        this.file = file;
    }

    /** Load from {@code configDir/aliases.yaml}; an absent or broken file yields empty aliases. */
    public static Aliases load(Path configDir)
    {
        Aliases aliases = new Aliases(configDir.resolve(FILENAME));
        aliases.reload();
        return aliases;
    }

    private synchronized void reload()
    {
        Yaml yaml = null;
        if (Files.exists(file))
        {
            try
            {
                yaml = YAML.readValue(file.toFile(), Yaml.class);
                LOG.info("Loaded aliases from {}", file.toAbsolutePath());
            }
            catch (Exception e)
            {
                // Aliases are an optimisation over raw matching, not a correctness
                // requirement: carry on with none rather than refusing to start.
                LOG.error("Could not read {} — continuing with no aliases: {}", file, e.toString());
            }
        }
        else
        {
            LOG.info("No {} — starting with no aliases", file.toAbsolutePath());
        }
        index(yaml);
    }

    // --- lookup --------------------------------------------------------------

    /**
     * Strip a known Australian country or fleet prefix and any leading zeros from a
     * normalised sail number, so {@code "AUS5656"}, {@code "0103"} and {@code "AUS00103"}
     * collapse to {@code "5656"}, {@code "103"} and {@code "103"}. Only strips the prefix
     * when a digit follows it.
     */
    public static String stripPrefix(String normSail)
    {
        if (normSail == null || normSail.isEmpty())
            return normSail;
        for (String prefix : AUS_PREFIXES)
        {
            if (normSail.startsWith(prefix) && normSail.length() > prefix.length()
                && Character.isDigit(normSail.charAt(prefix.length())))
            {
                normSail = normSail.substring(prefix.length());
                break;
            }
        }
        while (normSail.length() > 1 && normSail.charAt(0) == '0' && Character.isDigit(normSail.charAt(1)))
            normSail = normSail.substring(1);
        return normSail;
    }

    /** Canonical design id for a normalised design name; returns the input when unknown. */
    public synchronized String resolveDesignAlias(String normalisedName)
    {
        if (normalisedName == null || normalisedName.isBlank())
            return normalisedName;
        return Objects.requireNonNullElse(name2designId.get(normalisedName), normalisedName);
    }

    /** Display name for a canonical design id, or null when the seed does not name it. */
    public synchronized String designCanonicalName(String designId)
    {
        return designId2Name.get(designId);
    }

    /**
     * Resolve a raw (already normalised) sail/name pair to its canonical identity.
     * Empty when the pair is not an alias of anything — which is the common case, and
     * means "take the inputs at face value".
     */
    public synchronized Optional<BoatMatch> lookupBoat(String normSail, String normName)
    {
        // Sail number first, then confirm one of that boat's names matches.
        List<Indexed> entries = sailNo2Aliases.get(normSail);
        if (entries == null || entries.isEmpty())
            entries = sailNo2Aliases.get(stripPrefix(normSail));
        if (entries != null)
        {
            for (Indexed ib : entries)
            {
                for (SailNumberName alias : ib.entry().aliases())
                {
                    if (alias.name() != null && alias.name().equalsIgnoreCase(normName))
                        return Optional.of(ib.asMatch());
                }
            }
        }

        // Then by name, confirming the sail number. Prefix-strip both sides so an alias
        // stored as ("10001", "hamiltonislandwildoats") still matches an input of
        // "AUS10001": an alias keyed under the same sail as its canonical never reaches
        // the sail index above, so this branch is its only route.
        entries = name2Aliases.get(normName);
        if (entries != null)
        {
            String inputStripped = stripPrefix(normSail);
            for (Indexed ib : entries)
            {
                for (SailNumberName alias : ib.entry().aliases())
                {
                    if (alias.sailNumber() == null)
                        continue;
                    if (alias.sailNumber().equalsIgnoreCase(normSail)
                        || stripPrefix(alias.sailNumber()).equalsIgnoreCase(inputStripped))
                        return Optional.of(ib.asMatch());
                }
            }
        }

        // Implicit prefix equivalence — no YAML entry needed.
        String stripped = stripPrefix(normSail);
        if (!stripped.equals(normSail))
            return Optional.of(new BoatMatch(stripped, normName, null, false));

        return Optional.empty();
    }

    /** The recorded aliases for a canonical boat, or empty when it has none. */
    public synchronized List<SailNumberName> boatAliases(String normSail, String normName)
    {
        BoatEntry entry = canonicalIndex.get(normSail + "-" + normName);
        return entry == null ? List.of() : entry.aliases();
    }

    // --- write-back ----------------------------------------------------------

    /**
     * Record new aliases for a canonical boat and persist immediately.
     *
     * <p>Each new alias also acts as a pointer: if the file still holds an entry keyed by
     * that alias — an orphan left by a previous identity — its aliases are absorbed and
     * the orphan key removed. Without that, the next import seeing the orphan's alias
     * would resolve back to the merged-away identity and re-create the boat.
     *
     * <p>Comments in the file are not preserved; Jackson rewrites it wholesale.
     */
    public synchronized void addBoatAliases(String normSail, String canonicalName,
                                            List<SailNumberName> newAliases)
    {
        if (newAliases == null || newAliases.isEmpty())
            return;

        Yaml yaml = readForUpdate();
        if (yaml.boats == null)
            yaml.boats = new LinkedHashMap<>();

        String normName = IdGenerator.normaliseName(canonicalName);
        String key = normSail + "-" + normName;
        BoatEntry entry = yaml.boats.get(key);
        List<SailNumberName> existing = (entry != null && entry.aliases() != null)
            ? new ArrayList<>(entry.aliases()) : new ArrayList<>();

        for (SailNumberName alias : newAliases)
        {
            if (!contains(existing, alias))
                existing.add(alias);

            String orphanKey = alias.sailNumber() + "-" + alias.name();
            if (orphanKey.equalsIgnoreCase(key))
                continue;
            BoatEntry orphan = removeIgnoreCase(yaml.boats, orphanKey);
            if (orphan == null || orphan.aliases() == null)
                continue;
            for (SailNumberName o : orphan.aliases())
            {
                if (o.name() == null || o.name().isBlank())
                    continue;
                // A blank sail under the orphan key implicitly meant "the orphan's own
                // sail" — preserve that as it moves under the new key.
                String sail = (o.sailNumber() == null || o.sailNumber().isBlank())
                    ? alias.sailNumber() : o.sailNumber();
                if (sail.equalsIgnoreCase(normSail) && o.name().equalsIgnoreCase(normName))
                    continue;   // points back at the keep entry
                SailNumberName moved = new SailNumberName(sail, o.name());
                if (!contains(existing, moved))
                    existing.add(moved);
            }
        }

        yaml.boats.put(key, new BoatEntry(
            entry != null ? entry.canonicalName() : canonicalName, existing));
        write(yaml);
        index(yaml);
    }

    /** Record a design alias and persist immediately. */
    public synchronized void addDesignAlias(String designId, String canonicalName, String aliasName)
    {
        if (aliasName == null || aliasName.isBlank())
            return;
        Yaml yaml = readForUpdate();
        if (yaml.designs == null)
            yaml.designs = new LinkedHashMap<>();
        DesignEntry entry = yaml.designs.computeIfAbsent(designId, k -> new DesignEntry());
        if (entry.canonicalName == null && canonicalName != null)
            entry.canonicalName = canonicalName;
        if (entry.aliases == null)
            entry.aliases = new ArrayList<>();
        if (entry.aliases.stream().noneMatch(aliasName::equalsIgnoreCase))
            entry.aliases.add(aliasName);
        write(yaml);
        index(yaml);
    }

    private Yaml readForUpdate()
    {
        if (Files.exists(file))
        {
            try
            {
                return YAML.readValue(file.toFile(), Yaml.class);
            }
            catch (Exception e)
            {
                // Refuse to overwrite a file we could not parse: it may be a hand edit in
                // progress, and clobbering it would destroy every alias in it.
                LOG.error("Could not read {} for update — not writing: {}", file, e.toString());
                throw new IllegalStateException("aliases.yaml is unreadable; refusing to overwrite", e);
            }
        }
        return new Yaml();
    }

    private void write(Yaml yaml)
    {
        try
        {
            Files.createDirectories(file.getParent());
            YAML.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), yaml);
            LOG.info("Updated {}", file);
        }
        catch (IOException e)
        {
            LOG.error("Could not write {}: {}", file, e.toString());
        }
    }

    private static boolean contains(List<SailNumberName> list, SailNumberName candidate)
    {
        return list.stream().anyMatch(e ->
            Objects.equals(e.sailNumber(), candidate.sailNumber())
                && Objects.equals(e.name(), candidate.name()));
    }

    private static BoatEntry removeIgnoreCase(Map<String, BoatEntry> boats, String key)
    {
        BoatEntry direct = boats.remove(key);
        if (direct != null)
            return direct;
        for (String k : new ArrayList<>(boats.keySet()))
        {
            if (k.equalsIgnoreCase(key))
                return boats.remove(k);
        }
        return null;
    }

    // --- indexing ------------------------------------------------------------

    private void index(Yaml yaml)
    {
        Map<String, String> byName = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        Map<String, String> byId = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        if (yaml != null && yaml.designs != null)
        {
            for (Map.Entry<String, DesignEntry> e : yaml.designs.entrySet())
            {
                String id = e.getKey();
                DesignEntry entry = e.getValue();
                if (entry == null)
                    continue;
                if (entry.canonicalName != null)
                {
                    byId.put(id, entry.canonicalName);
                    byName.put(IdGenerator.normaliseDesignName(entry.canonicalName), id);
                }
                if (entry.aliases != null)
                {
                    for (String alias : entry.aliases)
                        byName.put(IdGenerator.normaliseDesignName(alias), id);
                }
            }
        }
        name2designId = Map.copyOf(byName);
        designId2Name = Map.copyOf(byId);

        Map<String, List<Indexed>> bySail = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        Map<String, List<Indexed>> byBoatName = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        Map<String, BoatEntry> canonical = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        if (yaml != null && yaml.boats != null)
        {
            for (Map.Entry<String, BoatEntry> e : yaml.boats.entrySet())
            {
                String key = e.getKey();
                BoatEntry entry = e.getValue();
                if (entry == null || entry.aliases() == null)
                    continue;
                int dash = key.indexOf('-');
                if (dash < 0)
                {
                    LOG.warn("Skipping alias entry with no dash in key: {}", key);
                    continue;
                }
                String canonSail = key.substring(0, dash);
                String canonName = key.substring(dash + 1);
                if (!IdGenerator.normaliseName(entry.canonicalName()).equalsIgnoreCase(canonName))
                {
                    LOG.warn("Skipping alias entry whose canonicalName disagrees with its key: {} vs {}",
                        key, entry.canonicalName());
                    continue;
                }

                List<SailNumberName> expanded = expand(entry.aliases(), canonSail, canonName);
                BoatEntry normalised = new BoatEntry(entry.canonicalName(), expanded);
                Indexed indexed = new Indexed(canonSail, canonName, normalised);
                canonical.put(key, normalised);

                for (SailNumberName alias : expanded)
                {
                    if (!canonSail.equalsIgnoreCase(alias.sailNumber()))
                        bySail.computeIfAbsent(alias.sailNumber(), k -> new ArrayList<>()).add(indexed);
                    if (!canonName.equalsIgnoreCase(alias.name()))
                        byBoatName.computeIfAbsent(alias.name(), k -> new ArrayList<>()).add(indexed);
                }
            }
        }
        sailNo2Aliases = bySail;
        name2Aliases = byBoatName;
        canonicalIndex = canonical;
    }

    /**
     * Turn the YAML's partial aliases into complete (sail, name) pairs. A sail-only alias
     * pairs with the canonical name and with every name-only alias; a name-only alias
     * pairs with the canonical sail. Sail×sail and name×name pairs are never emitted —
     * that would grow quadratically for a boat with many aliases of one kind.
     */
    private static List<SailNumberName> expand(List<SailNumberName> raw, String canonSail, String canonName)
    {
        List<SailNumberName> full = new ArrayList<>();
        List<String> sailOnly = new ArrayList<>();
        List<String> nameOnly = new ArrayList<>();
        for (SailNumberName a : raw)
        {
            if (a == null)
                continue;
            boolean hasSail = a.sailNumber() != null && !a.sailNumber().isBlank();
            boolean hasName = a.name() != null && !a.name().isBlank();
            if (hasSail && hasName)
                full.add(new SailNumberName(IdGenerator.normaliseSailNumber(a.sailNumber()),
                    IdGenerator.normaliseName(a.name())));
            else if (hasSail)
                sailOnly.add(IdGenerator.normaliseSailNumber(a.sailNumber()));
            else if (hasName)
                nameOnly.add(IdGenerator.normaliseName(a.name()));
        }

        LinkedHashSet<SailNumberName> out = new LinkedHashSet<>(full);
        for (String s : sailOnly)
        {
            out.add(new SailNumberName(s, canonName));
            for (String n : nameOnly)
                out.add(new SailNumberName(s, n));
        }
        for (String n : nameOnly)
            out.add(new SailNumberName(canonSail, n));
        return new ArrayList<>(out);
    }

    // --- types ---------------------------------------------------------------

    /**
     * A canonical identity resolved from an alias.
     *
     * <p>{@code fromSeed} distinguishes a recorded equivalence — someone wrote down that
     * these are the same boat — from the implicit country-prefix rule, which is just
     * normalisation. Callers report the first to the user and the second not at all.
     */
    public record BoatMatch(String normSailNumber, String normName, String canonicalDisplayName,
                            boolean fromSeed)
    {
    }

    /** One alias: either part may be null, meaning "the canonical one". */
    public record SailNumberName(String sailNumber, String name)
    {
    }

    public record BoatEntry(String canonicalName, List<SailNumberName> aliases)
    {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class DesignEntry
    {
        public String canonicalName;
        public List<String> aliases;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class Yaml
    {
        public Map<String, DesignEntry> designs;
        public Map<String, BoatEntry> boats;
    }

    private record Indexed(String canonSail, String canonName, BoatEntry entry)
    {
        BoatMatch asMatch()
        {
            return new BoatMatch(canonSail, canonName, entry.canonicalName(), true);
        }
    }
}
