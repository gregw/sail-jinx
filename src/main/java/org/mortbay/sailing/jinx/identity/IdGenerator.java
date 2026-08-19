package org.mortbay.sailing.jinx.identity;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Normalisation and ID slug utilities. All methods are pure functions.
 *
 * <p>Ported from the sailing-pf project so the two systems agree on what a boat
 * is called. See that project's Id-Strategy and Boat-Matching-Strategy wiki
 * pages for the reasoning; the short version is:
 *
 * <ul>
 *   <li>IDs are never derived from another system's internal identifiers.</li>
 *   <li>IDs are human-readable — you can tell what a record is without a lookup.</li>
 *   <li>IDs are stable once assigned, and where source data is dirty the raw
 *       forms are recorded as aliases rather than becoming new records.</li>
 * </ul>
 *
 * <p>Keeping these rules identical to sailing-pf matters because the same
 * boats, in the same fleet, are entered into both. A boat that normalises to
 * {@code 5656-mondo} here and something else there would be two boats.
 */
public final class IdGenerator
{
    /**
     * Decorative suffixes appearing after the last {@code -} in a boat name that carry no
     * identity information. The comparison is done in lowercase-and-non-alnum-removed form,
     * so the raw suffix can use any case and any internal whitespace — {@code -GM},
     * {@code -gm}, {@code "- Under 17"} and {@code "-UNDER  17"} all strip cleanly.
     *
     * <ul>
     *   <li>Grand-Master class markers: {@code l}, {@code m}, {@code gm}, {@code ggm}, {@code gggm}</li>
     *   <li>Youth division markers: {@code u16} … {@code under21}</li>
     * </ul>
     *
     * Add new tokens here when a new convention appears in the data.
     */
    private static final Set<String> STANDARD_SUFFIXES = Set.of(
        "l", "m", "gm", "ggm", "gggm",
        "u16", "u17", "u18", "u19", "u20", "u21",
        "under16", "under17", "under18", "under19", "under20", "under21");

    /**
     * Peels a trailing whitespace-separated numeral token off a cleaned raw name. Roman
     * numerals are accepted in either case. The whitespace before the numeral is required
     * so "Tivoli" (ending in "li") and "Anna" (ending in "a") are left intact — this is for
     * names like "Sticky II" / "Sticky 2", not embedded letter runs that look like Roman
     * digits.
     */
    private static final Pattern TRAILING_NUMERAL = Pattern.compile(
        "^(.*?)\\s+(\\d+|[IVXLCDMivxlcdm]+)$");

    private IdGenerator()
    {
    }

    /**
     * Strip decorative suffixes from a raw boat name (see {@link #STANDARD_SUFFIXES}).
     * Returns the input unchanged when no suffix matches. Iterates, so chains like
     * {@code "Boat - GM - U18"} collapse fully. Case and internal spacing of the surviving
     * portion are preserved so display names stay readable.
     */
    public static String stripStandardSuffixes(String raw)
    {
        if (raw == null)
            return null;
        while (true)
        {
            int dash = raw.lastIndexOf('-');
            if (dash < 0)
                return raw;
            String afterDash = raw.substring(dash + 1)
                .toLowerCase(Locale.ENGLISH)
                .replaceAll("[^a-z0-9]", "");
            if (!STANDARD_SUFFIXES.contains(afterDash))
                return raw;
            raw = raw.substring(0, dash).stripTrailing();
        }
    }

    /**
     * Lowercase, strip ALL non-{@code [a-z0-9]} characters including spaces and
     * punctuation: {@code "Raging Bull"} → {@code "ragingbull"}.
     *
     * <p>Decorative suffixes are removed first, so {@code "Foobar - GM"} and
     * {@code "Foobar"} both normalise to {@code "foobar"} and share a boat ID. This is the
     * single canonical normalisation used everywhere a name becomes part of an identifier.
     */
    public static String normaliseName(String raw)
    {
        if (raw == null)
            return "";
        return stripStandardSuffixes(raw).toLowerCase(Locale.ENGLISH).replaceAll("[^a-z0-9]", "");
    }

    /**
     * Lowercase, strip all non-alphanumerics. Collapses the common variants:
     * {@code "J/24"}, {@code "J 24"} and {@code "J24"} all become {@code "j24"}.
     */
    public static String normaliseDesignName(String raw)
    {
        if (raw == null)
            return "";
        return raw.toLowerCase(Locale.ENGLISH).replaceAll("[^a-z0-9]", "");
    }

    /**
     * Uppercase, strip all non-alphanumerics: {@code "AUS-1234"} → {@code "AUS1234"},
     * {@code "myc 7"} → {@code "MYC7"}.
     *
     * <p>The country prefix is <em>preserved</em> here and stripped implicitly during
     * matching — see {@link Aliases}.
     */
    public static String normaliseSailNumber(String raw)
    {
        if (raw == null)
            return "";
        return raw.toUpperCase(Locale.ENGLISH).replaceAll("[^A-Z0-9]", "");
    }

    /**
     * Boat ID: {@code {normalisedSailNumber}-{normalisedName}-{designId}}, with the design
     * omitted when unknown.
     *
     * <pre>
     * "AUS1234", "Raging Bull", null   → "AUS1234-ragingbull"
     * "AUS1234", "Raging Bull", "j24"  → "AUS1234-ragingbull-j24"
     * </pre>
     *
     * <p>A boat entered without a design therefore has a <em>different</em> ID from the
     * same boat once its design is known. That upgrade is handled by the registry, which
     * rewrites the existing record rather than creating a second one.
     */
    public static String generateBoatId(String rawSail, String rawName, String designId)
    {
        String normSail = normaliseSailNumber(rawSail);
        if (normSail.isEmpty())
            normSail = "nosail";
        String base = normSail + "-" + normaliseName(rawName);
        return (designId == null || designId.isBlank()) ? base : base + "-" + designId;
    }

    /**
     * Equivalence-class key for boat names sharing a sail number and design. Two raw names
     * with the same non-empty match key are the same boat. Returns an empty string when
     * the input collapses to nothing, so callers can skip the match-key path.
     *
     * <p>Operates on the raw form so word boundaries survive the lowercase pass:
     * <ol>
     *   <li>{@link #stripStandardSuffixes}</li>
     *   <li>lowercase</li>
     *   <li>drop a leading {@code "the "} (article plus space)</li>
     *   <li>repeatedly trim a trailing whitespace-separated Arabic or Roman numeral</li>
     *   <li>strip non-alphanumerics</li>
     * </ol>
     *
     * <pre>
     * "Goat"              → "goat"
     * "The Goat"          → "goat"
     * "Sticky 2"          → "sticky"
     * "Sticky II"         → "sticky"
     * "The Sticky 2 - GM" → "sticky"
     * "Tivoli"            → "tivoli"   (no whitespace before "li")
     * "Thelma"            → "thelma"   ("the" not followed by a space)
     * </pre>
     */
    public static String nameMatchKey(String raw)
    {
        if (raw == null || raw.isBlank())
            return "";
        String lower = stripStandardSuffixes(raw).toLowerCase(Locale.ENGLISH);
        if (lower.startsWith("the "))
            lower = lower.substring(4);
        while (true)
        {
            String stripped = lower.replaceFirst("\\s+(\\d+|[ivxlcdm]+)\\s*$", "");
            if (stripped.equals(lower))
                break;
            lower = stripped;
        }
        return lower.replaceAll("[^a-z0-9]", "");
    }

    /**
     * Choose the canonical display name from raw names that share a {@link #nameMatchKey}:
     * <ol>
     *   <li>strip decorative suffixes from each candidate;</li>
     *   <li>split each into {@code (body, numeral)} where the numeral is a trailing
     *       whitespace-separated Arabic or Roman number, or none;</li>
     *   <li>the longest {@code body} wins, ties broken in input order — so callers pass the
     *       incoming name first to prefer fresh data;</li>
     *   <li>an Arabic numeral seen anywhere beats a Roman one; none seen → none appended.</li>
     * </ol>
     *
     * <pre>
     * ["Goat", "The Goat"]           → "The Goat"
     * ["Sticky", "Sticky II"]        → "Sticky II"
     * ["Sticky 2", "Sticky II"]      → "Sticky 2"     (Arabic preferred)
     * ["Foobar - GM", "Foobar"]      → "Foobar"
     * ["The Sticky 2 - GM", "Sticky"]→ "The Sticky 2"
     * </pre>
     */
    public static String preferredDisplayName(Iterable<String> rawNames)
    {
        record Split(String body, String numeral) {}
        List<Split> splits = new ArrayList<>();
        for (String raw : rawNames)
        {
            if (raw == null)
                continue;
            String cleaned = stripStandardSuffixes(raw);
            if (cleaned == null || cleaned.isBlank())
                continue;
            Matcher m = TRAILING_NUMERAL.matcher(cleaned);
            if (m.matches())
                splits.add(new Split(m.group(1), m.group(2)));
            else
                splits.add(new Split(cleaned, null));
        }
        if (splits.isEmpty())
            return "";

        Split longest = splits.getFirst();
        for (Split s : splits)
        {
            if (s.body.length() > longest.body.length())
                longest = s;
        }

        String chosen = null;
        for (Split s : splits)
        {
            if (s.numeral != null && s.numeral.matches("\\d+"))
            {
                chosen = s.numeral;
                break;
            }
        }
        if (chosen == null)
        {
            for (Split s : splits)
            {
                if (s.numeral != null)
                {
                    chosen = s.numeral;
                    break;
                }
            }
        }
        return chosen == null ? longest.body : longest.body + " " + chosen;
    }

    /**
     * Lowercase, replace runs of non-alphanumerics with a single hyphen, trim leading and
     * trailing hyphens: {@code "Main Series 2018-19"} → {@code "main-series-2018-19"}.
     */
    public static String normaliseSeriesName(String raw)
    {
        if (raw == null)
            return "";
        return raw.toLowerCase(Locale.ENGLISH)
            .replaceAll("[^a-z0-9]+", "-")
            .replaceAll("^-|-$", "");
    }

    /**
     * Series ID: {@code {clubDomain}/{normalisedSeriesName}} —
     * {@code "myc.org.au/2026-winter-twilight"}.
     */
    public static String generateSeriesId(String clubDomain, String seriesName)
    {
        return clubDomain + "/" + normaliseSeriesName(seriesName);
    }

    /**
     * Race ID: {@code {clubDomain}-{isoDate}-{nnnn}} —
     * {@code "myc.org.au-2026-06-05-0001"}.
     *
     * <p>A race's identity is not cleanly derivable from its series, because a race can
     * belong to more than one. The club and date are stable; the number distinguishes
     * several races on one day.
     */
    public static String generateRaceId(String clubDomain, LocalDate date, int number)
    {
        return clubDomain + "-" + date + String.format("-%04d", number);
    }

    /**
     * Replaces {@code /} with {@code --} so an ID can be used as a filename.
     * {@code "myc.org.au/twilight"} → {@code "myc.org.au--twilight"}.
     *
     * <p>Series IDs are the only ones that carry a slash, and they key files under
     * {@code roster/} and {@code series-config/}.
     */
    public static String sanitizeIdForFilesystem(String id)
    {
        return id == null ? "" : id.replace("/", "--");
    }
}
