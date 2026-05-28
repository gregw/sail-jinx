package org.mortbay.sailing.jinx.model;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Race officer's locally-captured times for one race.
 *
 * <p>This is the JSON that the Race page persists when the user clicks SAVE on
 * the current race: the drag-ordered list of boats, the optional duty boat
 * (rotated through the fleet — gets the AVG flag and its times are ignored),
 * and per boat whether the boat came to the start ({@code came}) and the
 * wall-clock {@code actualStart} and {@code finish}. Times are kept as the
 * raw {@code HH:MM:SS} strings the RO typed (or that the NOW button stamped)
 * so we round-trip exactly what was entered without timezone or parsing drift.
 *
 * <p>{@code dutyBoatId} is nullable — most races don't have one captured yet.
 * Older files written before the field existed deserialize with it as null.
 *
 * <p>{@code divisionStarts} maps SailSys divisionId → "HH:MM:SS" actual start.
 * Populated for non-pursuit races, where every boat in a division shares one
 * gun time and the per-boat {@code actualStart} is therefore not captured.
 * Null/empty for pursuit races, where each boat has its own staggered start
 * recorded under {@link BoatTimes#actualStart}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RaceTimes(
    String raceId,
    List<String> boatOrder,
    String dutyBoatId,
    Map<String, String> divisionStarts,
    Map<String, BoatTimes> times)
{
    public record BoatTimes(
        boolean came,
        String actualStart,
        String finish)
    {
    }
}
