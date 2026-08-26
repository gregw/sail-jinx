package org.mortbay.sailing.jinx.identity;

import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.mortbay.sailing.jinx.model.Boat;
import org.mortbay.sailing.jinx.model.Design;
import org.mortbay.sailing.jinx.model.Spinnaker;
import org.mortbay.sailing.jinx.store.JsonStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Turns whatever someone typed into a boat in the register — the same hull every time,
 * however it was spelled.
 *
 * <p>This is the only way boats should be created. Going straight to
 * {@link JsonStore#putBoat} bypasses alias resolution and design learning, and the result
 * is two records for one hull with half the handicap history each.
 *
 * <p>The order of operations matters and mirrors sailing-pf's {@code findOrCreateBoat}:
 *
 * <ol>
 *   <li>normalise the sail number and name, stripping decorative division suffixes;</li>
 *   <li>resolve boat aliases, so a sponsor name or an old sail number lands on the
 *       canonical identity;</li>
 *   <li>resolve the design: alias first, then any per-boat override — which is keyed by
 *       the <em>post-alias</em> sail and name — then drop it if it is a generic label;</li>
 *   <li>look for an existing boat by sail and name, with the design-compatibility rules
 *       below;</li>
 *   <li>failing that, look again allowing name variants ({@code Sticky} ≡ {@code Sticky II});</li>
 *   <li>create, or upgrade what was found.</li>
 * </ol>
 *
 * <h2>Design compatibility</h2>
 * Given a candidate with the same sail and name:
 * <ul>
 *   <li><b>same design</b> — the same boat;</li>
 *   <li><b>candidate has none, incoming has one</b> — the same boat, and the record is
 *       <em>upgraded</em>: its id gains the design and every reference is rewritten.
 *       This is the case that matters for a CSV import where the design column was blank
 *       the first time and filled in later;</li>
 *   <li><b>candidate has one, incoming has none</b> — the same boat; the known design
 *       wins over the missing one;</li>
 *   <li><b>two different designs</b> — <em>not</em> matched. Either two boats really do
 *       share a sail number and name, or the data is wrong; guessing would merge two
 *       hulls. The conflict is reported so a person can add an override.</li>
 * </ul>
 */
public class BoatRegistry
{
    private static final Logger LOG = LoggerFactory.getLogger(BoatRegistry.class);

    private final JsonStore store;
    private final Aliases aliases;
    private final DesignCatalogue catalogue;

    public BoatRegistry(JsonStore store, Aliases aliases, DesignCatalogue catalogue)
    {
        this.store = store;
        this.aliases = aliases;
        this.catalogue = catalogue;
    }

    /** What happened to one boat, so a bulk import can report per row. */
    public enum Outcome
    {
        /** No such boat before; a record was created. */
        CREATED,
        /** Recognised, nothing about it changed. */
        MATCHED,
        /** Recognised, and its design became known — the id changed with it. */
        UPGRADED,
        /** Recognised under a different spelling, which has been recorded as an alias. */
        ALIASED,
        /** Two different designs claim this sail and name; a person must decide. */
        CONFLICT
    }

    /**
     * The result of resolving one raw entry. {@code boat} is null only for
     * {@link Outcome#CONFLICT}.
     */
    public record Resolution(Outcome outcome, Boat boat, String note)
    {
        public boolean resolved()
        {
            return boat != null;
        }
    }

    /**
     * A boat's identity as typed, before any normalisation.
     *
     * <p>Only what belongs to the hull. TCF, division and spinnaker are terms of a
     * race entry, not facts about a boat, so they are not here — a caller importing a
     * fleet list applies those to the entrant list itself. See {@link Boat}.
     */
    public record RawBoat(
        String sailNumber,
        String name,
        String design,
        String notes,
        boolean casual)
    {
    }

    /**
     * Find the boat this entry refers to, creating or upgrading the register as needed.
     *
     * @param raw  the entry exactly as typed
     * @param date the race date for a time-bounded design override, or null
     */
    public synchronized Resolution findOrCreate(RawBoat raw, LocalDate date) throws IOException
    {
        String displayName = IdGenerator.stripStandardSuffixes(
            raw.name() == null ? "" : raw.name().trim());
        String normSail = IdGenerator.normaliseSailNumber(raw.sailNumber());
        String normName = IdGenerator.normaliseName(raw.name());

        if (normSail.isEmpty() && normName.isEmpty())
            return new Resolution(Outcome.CONFLICT, null, "no sail number and no name");

        // Aliases: this spelling may be a known alias of a different identity.
        boolean viaAlias = false;
        Optional<Aliases.BoatMatch> alias = aliases.lookupBoat(normSail, normName);
        if (alias.isPresent())
        {
            Aliases.BoatMatch m = alias.get();
            viaAlias = m.fromSeed();
            normSail = m.normSailNumber();
            normName = m.normName();
            if (m.canonicalDisplayName() != null && !m.canonicalDisplayName().isBlank())
                displayName = m.canonicalDisplayName();
        }

        String designId = resolveDesign(raw.design(), normSail, normName, date);

        Candidate found = findCandidate(normSail, normName, displayName, designId);
        if (found != null && found.conflict())
        {
            LOG.warn("Design conflict for {} {}: existing '{}' vs incoming '{}'",
                normSail, normName, found.boat().designId(), designId);
            return new Resolution(Outcome.CONFLICT, null,
                "already registered as design '" + found.boat().designId()
                    + "' but this entry says '" + designId + "'");
        }

        if (found == null)
        {
            Boat created = create(normSail, displayName, designId, raw);
            learnDesign(designId, raw.design());
            return new Resolution(Outcome.CREATED, created,
                designId == null ? null : "design " + designId);
        }

        Boat boat = found.boat();
        String note = null;
        Outcome outcome = viaAlias ? Outcome.ALIASED : Outcome.MATCHED;

        // A name variant that is not yet written down becomes one, so the next import of
        // either spelling resolves here without rediscovering it.
        if (found.viaNameVariant())
        {
            String was = boat.name();
            // The incoming name goes first so fresh data wins an equal-length tie.
            String preferred = IdGenerator.preferredDisplayName(List.of(displayName, was));
            aliases.addBoatAliases(normSail, preferred, List.of(
                new Aliases.SailNumberName(normSail, IdGenerator.normaliseName(displayName)),
                new Aliases.SailNumberName(normSail, IdGenerator.normaliseName(was))));
            // Settle the register on that same canonical name. Leaving the boat as
            // "Sticky" while the alias file calls it "Sticky II" would be two answers to
            // one question, and the id is built from the name so it moves too.
            if (!preferred.equals(was))
                boat = rename(boat, preferred);
            outcome = Outcome.ALIASED;
            note = "matched '" + was + "' as a variant of '" + displayName + "'"
                + (preferred.equals(was) ? "" : "; now called '" + preferred + "'");
        }
        else if (viaAlias)
        {
            note = "resolved by alias to " + boat.sailNumber() + " " + boat.name();
        }

        // The upgrade: a design-less record learns its design, so its id changes.
        if (boat.designId() == null && designId != null)
        {
            boat = upgradeDesign(boat, designId);
            learnDesign(designId, raw.design());
            return new Resolution(Outcome.UPGRADED, boat,
                "design " + designId + " — id is now " + boat.id());
        }

        return new Resolution(outcome, boat, note);
    }

    /**
     * Record an alias that the caller discovered, e.g. the raw spelling a CSV used for a
     * boat that resolved to a different canonical identity. Persisted immediately.
     */
    public synchronized void recordAlias(Boat boat, String rawSail, String rawName)
    {
        String normSail = IdGenerator.normaliseSailNumber(rawSail);
        String normName = IdGenerator.normaliseName(rawName);
        String canonSail = IdGenerator.normaliseSailNumber(boat.sailNumber());
        String canonName = IdGenerator.normaliseName(boat.name());
        if (normSail.equals(canonSail) && normName.equals(canonName))
            return;
        aliases.addBoatAliases(canonSail, boat.name(),
            List.of(new Aliases.SailNumberName(normSail, normName)));
    }

    // --- internals -----------------------------------------------------------

    /**
     * Alias → per-boat override → ignored check. Returns null when the design is unknown
     * or is a label we refuse to treat as a design.
     */
    private String resolveDesign(String rawDesign, String normSail, String normName, LocalDate date)
    {
        String designId = null;
        if (rawDesign != null && !rawDesign.isBlank())
            designId = aliases.resolveDesignAlias(IdGenerator.normaliseDesignName(rawDesign));

        // The override is keyed on the post-alias identity and wins over what was typed:
        // it is a person's considered correction of exactly this hull.
        String override = catalogue.resolveOverride(normSail, normName, date);
        if (override != null)
            designId = override;

        if (designId == null || designId.isBlank())
            return null;
        if (catalogue.isIgnored(designId))
        {
            // "yacht", "sloop", "custom" — not a design. Clearing it keeps the boat's
            // history in one record instead of splitting it against a meaningless id.
            LOG.debug("Ignoring generic design '{}' for {} {}", designId, normSail, normName);
            return null;
        }
        return designId;
    }

    /** A candidate match, or a design conflict. */
    private record Candidate(Boat boat, boolean viaNameVariant, boolean conflict)
    {
    }

    private Candidate findCandidate(String normSail, String normName, String rawName, String designId)
    {
        String strippedSail = Aliases.stripPrefix(normSail);
        List<Boat> sameSail = new ArrayList<>();
        for (Boat b : store.boats().values())
        {
            String bSail = IdGenerator.normaliseSailNumber(b.sailNumber());
            if (bSail.equalsIgnoreCase(normSail)
                || Aliases.stripPrefix(bSail).equalsIgnoreCase(strippedSail))
            {
                sameSail.add(b);
            }
        }
        if (sameSail.isEmpty())
            return null;

        // Exact name first — a unique exact hit must never be widened into ambiguity
        // by the variant pass below.
        List<Boat> exact = sameSail.stream()
            .filter(b -> IdGenerator.normaliseName(b.name()).equalsIgnoreCase(normName))
            .toList();
        if (!exact.isEmpty())
            return classify(exact, designId, false);

        // The match key must be computed from the RAW name: normalising first would
        // strip the whitespace that separates a trailing numeral, and "Sticky II" would
        // never collapse onto "Sticky".
        String matchKey = IdGenerator.nameMatchKey(rawName);
        if (matchKey.isEmpty())
            return null;
        List<Boat> variants = sameSail.stream()
            .filter(b -> matchKey.equals(IdGenerator.nameMatchKey(b.name())))
            .toList();
        if (variants.isEmpty())
            return null;
        if (variants.size() > 1)
        {
            LOG.warn("Ambiguous name-variant match for {} {}: {} candidates", normSail, normName,
                variants.size());
            return null;
        }
        return classify(variants, designId, true);
    }

    private Candidate classify(List<Boat> candidates, String designId, boolean viaVariant)
    {
        // Prefer a candidate whose design already agrees, then a design-less one that can
        // absorb ours.
        for (Boat b : candidates)
        {
            if (designId != null && designId.equals(b.designId()))
                return new Candidate(b, viaVariant, false);
        }
        for (Boat b : candidates)
        {
            if (b.designId() == null || designId == null)
                return new Candidate(b, viaVariant, false);
        }
        return new Candidate(candidates.getFirst(), viaVariant, true);
    }

    private Boat create(String normSail, String displayName, String designId, RawBoat raw)
        throws IOException
    {
        String id = IdGenerator.generateBoatId(normSail, displayName, designId);
        Boat boat = new Boat(id, normSail, displayName, designId, raw.casual(), true, raw.notes());
        store.putBoat(boat);
        LOG.info("Registered boat {}", id);
        return boat;
    }

    /**
     * The spinnaker a boat should default to when entering a series: none when its design
     * physically cannot fly one, otherwise <b>nothing</b>.
     *
     * <p>The asymmetry is the point. A design marked {@code noSpinnaker} is a hull fact,
     * so NS is known. The absence of that mark is not the opposite fact — it says only
     * that nobody has recorded anything, and a boat that <em>could</em> fly a kite may
     * well not be flying one. Returning S there invented an answer for every hull in the
     * register, so a fleet nobody had ever been asked about displayed as if the whole
     * lot carried spinnakers.
     *
     * <p>Only a default in any case — the entry decides, and the series policy answers
     * first where the series is one-way.
     */
    public Spinnaker defaultSpinnaker(String designId)
    {
        return catalogue.isNoSpinnaker(designId) ? Spinnaker.NS : null;
    }

    /**
     * Settle a boat on a new display name. The name is part of the id, so this moves the
     * record and every reference to it.
     */
    private Boat rename(Boat boat, String name) throws IOException
    {
        String newId = IdGenerator.generateBoatId(boat.sailNumber(), name, boat.designId());
        Boat renamed = new Boat(newId, boat.sailNumber(), name, boat.designId(),
            boat.casual(), boat.active(), boat.notes());
        store.putBoat(renamed);
        store.rewriteBoatId(boat.id(), newId);
        LOG.info("Renamed boat {} -> {} ({})", boat.id(), newId, name);
        return renamed;
    }

    /**
     * Give a design-less boat its design. The design is part of the id, so the record
     * moves — and every reference to it has to move too, or the boat's races are orphaned.
     */
    private Boat upgradeDesign(Boat boat, String designId) throws IOException
    {
        String newId = IdGenerator.generateBoatId(boat.sailNumber(), boat.name(), designId);
        Boat upgraded = new Boat(newId, boat.sailNumber(), boat.name(), designId,
            boat.casual(), boat.active(), boat.notes());
        store.putBoat(upgraded);
        store.rewriteBoatId(boat.id(), newId);
        LOG.info("Upgraded boat {} to {} on learning design {}", boat.id(), newId, designId);
        return upgraded;
    }

    /**
     * Remember a design the first time it is seen. Designs are only ever learned this
     * way — there is no design-entry screen — so the display name is whatever the alias
     * seed calls it, or the first spelling anyone typed.
     */
    private void learnDesign(String designId, String rawDesign) throws IOException
    {
        if (designId == null || store.designs().containsKey(designId))
            return;
        String canonical = aliases.designCanonicalName(designId);
        if (canonical == null)
            canonical = catalogue.overrideDesignName(designId);
        if (canonical == null)
            canonical = (rawDesign == null || rawDesign.isBlank()) ? designId : rawDesign.trim();
        store.putDesign(new Design(designId, canonical, List.of(),
            catalogue.isNoSpinnaker(designId)));
        LOG.info("Learned design {} ({})", designId, canonical);
    }
}
