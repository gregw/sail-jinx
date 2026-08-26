package org.mortbay.sailing.jinx.pursuit;

/**
 * A boat as the handicap engine sees it: an identity to key the answer by, and the TCF
 * in force for the race being computed.
 *
 * <p>Deliberately not {@link org.mortbay.sailing.jinx.model.Boat}. A boat has no TCF —
 * it has one per series entry, and a different one by the end of the season. Handing the
 * engine a Boat would mean inventing a handicap field on the register just to have
 * somewhere to put the value while it is passed along.
 *
 * <p>Nothing else about the boat is relevant here: the algorithm works on handicaps and
 * elapsed times, and does not care what the boat is called or what it was built as.
 */
public record Competitor(String boatId, double tcf, boolean seeded)
{
    /**
     * A boat that was on the start sheet before the night began, and so takes part in the
     * handicap. A boat that turned up and raced without being seeded is scored for the
     * night but left out of the handicap arithmetic entirely — it is not in the placings,
     * it neither pays into the pool nor draws from it, and its elapsed time does not
     * reach the penalties the rest of the fleet pays.
     */
    public Competitor
    {
    }

    /** A seeded boat — the ordinary case, and what every caller meant before the flag. */
    public Competitor(String boatId, double tcf)
    {
        this(boatId, tcf, true);
    }
}
