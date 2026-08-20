package org.mortbay.sailing.jinx.identity;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.mortbay.sailing.jinx.model.Spinnaker;

/**
 * Reads a fleet export from the sailing-pf project — the {@code handicaps-YYYY-MM-DD.json}
 * files it produces:
 *
 * <pre>
 * [ { "boatId": "5656-mondo-sydney38", "sailno": "5656", "name": "MONDO",
 *     "handicap": 1.0809, "variant": "spin" } ]
 * </pre>
 *
 * <p>The useful part is {@code boatId}: sailing-pf mints it with the same rules sail-jinx
 * uses, so it is not an opaque foreign key but a statement of identity we can read —
 * including the design as its trailing segment. A boat we hold without a design can
 * therefore be <em>upgraded</em> from one of these files rather than being duplicated.
 *
 * <p>Every field is still put through the normal matching (see {@link BoatRegistry}): the
 * export is evidence, not instruction. A sail number or name that has since changed
 * resolves through {@code aliases.yaml} exactly as any other entry would.
 */
public final class FleetJson
{
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private FleetJson()
    {
    }

    /**
     * One boat from the export: its identity, the id the source gave it, and the terms
     * of its entry. Callers that only want the fleet register ignore the terms.
     */
    public record Row(
        int index,
        String sourceBoatId,
        BoatRegistry.RawBoat boat,
        Double handicap,
        Spinnaker spinnaker)
    {
    }

    /** The rows, plus anything about the file worth telling the user. */
    public record Parsed(List<Row> rows, List<String> problems)
    {
    }

    /**
     * Parse the export. Never throws: a row that cannot be read becomes a problem message
     * and the rest of the file still imports, because one bad entry in a fleet list should
     * not cost the other twenty-two.
     */
    public static Parsed parse(String json)
    {
        List<Row> rows = new ArrayList<>();
        List<String> problems = new ArrayList<>();

        if (json == null || json.isBlank())
        {
            problems.add("the file is empty");
            return new Parsed(rows, problems);
        }

        JsonNode root;
        try
        {
            root = MAPPER.readTree(json);
        }
        catch (Exception e)
        {
            problems.add("this is not JSON: " + e.getMessage());
            return new Parsed(rows, problems);
        }

        // Tolerate the array being wrapped, since an export may grow a header later.
        if (root.isObject())
        {
            for (String field : List.of("boats", "handicaps", "fleet", "rows"))
            {
                if (root.path(field).isArray())
                {
                    root = root.path(field);
                    break;
                }
            }
        }
        if (!root.isArray())
        {
            problems.add("expected a JSON array of boats, e.g. "
                + "[{\"boatId\":\"…\",\"sailno\":\"…\",\"name\":\"…\"}]");
            return new Parsed(rows, problems);
        }

        for (int i = 0; i < root.size(); i++)
        {
            JsonNode node = root.get(i);
            int shown = i + 1;
            if (!node.isObject())
            {
                problems.add("entry " + shown + " is not an object — skipped");
                continue;
            }
            String sail = text(node, "sailno", "sailNumber", "sail");
            String name = text(node, "name", "boatName", "boat");
            String sourceId = text(node, "boatId", "id");

            if (isBlank(sail) && isBlank(name))
            {
                problems.add("entry " + shown + " has no sail number and no name — skipped");
                continue;
            }

            String design = designFromBoatId(sourceId, sail, name);
            if (design == null && !isBlank(sourceId) && !idMatchesSailAndName(sourceId, sail, name))
            {
                // The id disagrees with the sail and name beside it. We will not invent a
                // design out of an id we cannot read; matching falls back to sail + name.
                problems.add("entry " + shown + " (" + sourceId + "): the id does not match its "
                    + "sail number and name, so no design was taken from it");
            }

            rows.add(new Row(shown, blankToNull(sourceId),
                new BoatRegistry.RawBoat(sail, name, design, null, false),
                node.hasNonNull("handicap") ? node.path("handicap").asDouble() : null,
                spinnakerOf(text(node, "variant", "spinnaker", "spin"))));
        }

        if (rows.isEmpty() && problems.isEmpty())
            problems.add("the file has no boats in it");
        return new Parsed(rows, problems);
    }

    /**
     * The design segment of a sailing-pf boat id.
     *
     * <p>The id is {@code {normSail}-{normName}-{designId}} with the design omitted when
     * unknown, so the design is whatever follows the sail and name. Rebuilding that prefix
     * from the row's own sail and name is what makes this safe: a name containing hyphens
     * ("Manly Sailing - Supernova") would defeat splitting on the last hyphen, and an id
     * that disagrees with its row is rejected rather than guessed at.
     *
     * @return the design id, or null when there is none or the id cannot be read
     */
    static String designFromBoatId(String boatId, String sail, String name)
    {
        if (isBlank(boatId))
            return null;
        String prefix = IdGenerator.normaliseSailNumber(sail) + "-" + IdGenerator.normaliseName(name);
        if (boatId.length() > prefix.length() + 1
            && boatId.regionMatches(true, 0, prefix + "-", 0, prefix.length() + 1))
        {
            String design = boatId.substring(prefix.length() + 1);
            return design.isBlank() ? null : design;
        }
        return null;
    }

    /** True when the id is exactly this boat's sail and name, i.e. a design-less id. */
    private static boolean idMatchesSailAndName(String boatId, String sail, String name)
    {
        String prefix = IdGenerator.normaliseSailNumber(sail) + "-" + IdGenerator.normaliseName(name);
        return boatId.equalsIgnoreCase(prefix);
    }

    /** "spin" / "nonspin" / "ns" / "no". Anything starting with "n" means no kite. */
    static Spinnaker spinnakerOf(String variant)
    {
        if (isBlank(variant))
            return null;
        String v = variant.trim().toLowerCase(Locale.ENGLISH);
        if (v.startsWith("non") || v.equals("ns") || v.startsWith("no"))
            return Spinnaker.NS;
        if (v.startsWith("s") || v.startsWith("k") || v.startsWith("y"))
            return Spinnaker.S;
        return null;
    }

    private static String text(JsonNode node, String... fields)
    {
        for (String f : fields)
        {
            JsonNode v = node.path(f);
            if (!v.isMissingNode() && !v.isNull() && !v.asText().isBlank())
                return v.asText().trim();
        }
        return null;
    }

    private static boolean isBlank(String s)
    {
        return s == null || s.isBlank();
    }

    private static String blankToNull(String s)
    {
        return isBlank(s) ? null : s;
    }
}
