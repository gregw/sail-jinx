package org.mortbay.sailing.jinx.model;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Race officer's captured times for one race, stored as
 * {@code data/store/race-times/{raceId}.json}.
 *
 * <p>What the Race page persists when the user clicks SAVE: the drag-ordered
 * list of boats, the optional duty boat (rotated through the fleet — gets the
 * AVG flag and its times are ignored), and per boat whether it came to the
 * start ({@code came}) plus the wall-clock {@code actualStart} and
 * {@code finish}.
 *
 * <p>Times are kept as the raw {@code HH:MM:SS} strings the RO typed, or that
 * the NOW button stamped, so we round-trip exactly what was entered without
 * timezone or parsing drift. Elapsed and corrected times are always derived,
 * never stored.
 *
 * <p>{@code dutyBoatId} is nullable — most races don't have one captured yet.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RaceTimes(
    String raceId,
    /**
     * The race page's manual row order — <b>read, no longer written</b>.
     *
     * <p>Dragging a row is how the table is arranged, not something a boat did, so it
     * marked the page dirty and offered to save a change nobody had made. The order
     * moved to the per-race view in session storage, beside the column sort, which is
     * remembered without being saved.
     *
     * <p>The field stays because races saved before that still carry one, and the page
     * uses it to seed a tab that has not dragged anything yet. New saves leave it empty.
     * {@code JsonStore.rewriteBoatId} still maintains it so an old order survives a
     * design upgrade rather than half-pointing at a renamed boat.
     */
    List<String> boatOrder,
    String dutyBoatId,
    Map<String, BoatTimes> times)
{
    public RaceTimes
    {
        if (boatOrder == null)
            boatOrder = List.of();
        if (times == null)
            times = Map.of();
    }

    /**
     * What was captured against one boat, and what the race officer said about it.
     *
     * <p>{@code flags} is the RO's overrides, not the boat's effective flags. Most flags
     * are <em>derived</em> from the three fields above it — came, started, finished — and
     * deriving them is right: they follow from the times and would go stale the moment a
     * time was corrected. What cannot be derived is a judgement that contradicts the
     * times, and that is what this holds.
     *
     * <p>The case it exists for: a boat that came, started and never finished reads as
     * DNF, and DNF eases a handicap because running out of time is about speed. RET says
     * the boat stopped for a reason of its own, which says nothing about its speed and
     * freezes the handicap instead. Nothing else on the page can tell those apart, so
     * without this the RO's RET survived only as long as the browser tab.
     */
    public record BoatTimes(
        boolean came,
        String actualStart,
        String finish,
        FlagOverride flags)
    {
        /** Times with nothing overridden — the ordinary case. */
        public BoatTimes(boolean came, String actualStart, String finish)
        {
            this(came, actualStart, finish, null);
        }
    }

    /**
     * Flags the race officer added or cleared by hand.
     *
     * <p>Added <em>and</em> removed, rather than one list of effective flags, because a
     * derived flag has to be clearable: an OCS that came from a mistyped start time is
     * taken off by fixing the time, but a genuine one the RO wants gone needs somewhere
     * to be recorded as gone. A single list could not express "not this one".
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record FlagOverride(List<String> added, List<String> removed)
    {
        public FlagOverride
        {
            if (added == null)
                added = List.of();
            if (removed == null)
                removed = List.of();
        }

        /** Nothing was overridden; there is no reason to write this out. */
        public boolean isEmpty()
        {
            return added.isEmpty() && removed.isEmpty();
        }
    }
}
