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

    public record BoatTimes(
        boolean came,
        String actualStart,
        String finish)
    {
    }
}
