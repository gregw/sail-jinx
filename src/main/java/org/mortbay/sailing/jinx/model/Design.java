package org.mortbay.sailing.jinx.model;

import java.util.List;

/**
 * A boat design — the hull type, e.g. J/24, Farr 40, Sydney 38.
 *
 * <p>The id is the normalised design name ({@code j24}, {@code farr40}), matching the
 * sailing-pf convention so the same hull carries the same key in both systems.
 *
 * <p>Designs are <b>learned, never entered</b>. There is no design-management screen:
 * a design comes into existence because someone typed one while adding a boat, and its
 * canonical display name is whatever the alias seed says or, failing that, the first
 * spelling seen. That keeps the fleet register the only thing the club has to maintain.
 *
 * <p>sail-jinx does not currently score by design — the Jinx handicap works on TCF alone.
 * It is recorded because a handicapper wants to know that three boats sharing a design
 * are drifting apart, and because it disambiguates two boats with the same sail number.
 *
 * <p>{@code noSpinnaker} marks a design that physically cannot fly one (a cat rig, for
 * instance), which is a property of the hull rather than a choice made on the night.
 */
public record Design(
    String id,
    String canonicalName,
    List<String> aliases,
    boolean noSpinnaker)
{
    public Design
    {
        if (aliases == null)
            aliases = List.of();
    }

    /** Display form: the canonical name when known, else the id. */
    public String displayName()
    {
        return (canonicalName == null || canonicalName.isBlank()) ? id : canonicalName;
    }
}
