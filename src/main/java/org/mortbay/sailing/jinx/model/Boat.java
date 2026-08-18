package org.mortbay.sailing.jinx.model;

/**
 * A boat in the club's fleet register — roughly forty of them, edited by hand
 * on the Boats page.
 *
 * <p>{@code id} is minted locally and never changes; entrants and TCF history
 * reference it. Retiring a boat sets {@code active} false rather than deleting
 * it, because past races still have to render its name and sail number.
 *
 * <p>{@code currentTcf} is the register's <b>seed</b> value — what this boat
 * starts a new series on, and what a mid-season casual gets handed. It is
 * <em>not</em> authoritative for any race: the TCF actually used is the one on
 * that race's {@link Entrant}, which is why race 4's handicaps survive race 5
 * being processed.
 *
 * <p>{@code division} is a free-text label kept for display and for a possible
 * future fleet-start mode. The pursuit algorithm ignores it: in a pursuit race
 * every boat is staggered individually, so there is nothing for a division to
 * do.
 *
 * <p>{@code casual} marks a boat added on a race night rather than registered
 * before the season. It changes nothing functionally — it just lets the Boats
 * page show who arrived the informal way, so the register can be tidied later.
 */
public record Boat(
    String id,
    String sailNumber,
    String name,
    String division,
    Spinnaker spinnaker,
    double currentTcf,
    boolean casual,
    boolean active,
    String notes)
{
    public Boat
    {
        currentTcf = Tcf.round(currentTcf);
    }
}
