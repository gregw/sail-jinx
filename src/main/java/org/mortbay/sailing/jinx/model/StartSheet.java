package org.mortbay.sailing.jinx.model;

import java.time.Instant;
import java.time.LocalTime;
import java.util.List;

/**
 * The computed pursuit start times for one race, stored as
 * {@code data/store/start-sheet/{raceId}.json}.
 *
 * <p>Persisted rather than recomputed on demand, because it is <em>published</em>:
 * once the fleet has been told when it starts, editing a TCF must not silently
 * move anybody's gun. Recomputing is an explicit action, and the inputs that
 * produced the sheet ({@code targetElapsedMinutes}, {@code earliestStart}) are
 * kept alongside it so a stale sheet can be recognised as stale.
 *
 * <p>{@code starts} is ordered slowest boat first, which is both the order they
 * start in and the order the start-offset report prints.
 */
public record StartSheet(
    String raceId,
    Instant computedAt,
    int targetElapsedMinutes,
    LocalTime earliestStart,
    List<StartTime> starts)
{
    public StartSheet
    {
        if (starts == null)
            starts = List.of();
    }

    /**
     * Whole minutes between {@code earliestStart} and the given boat's gun —
     * the {@code +0 / +6 / +13} figure the start-offset report prints, and the
     * form the club's other system takes it in.
     */
    public long offsetMinutes(StartTime start)
    {
        return java.time.Duration.between(earliestStart, start.startTime()).toMinutes();
    }
}
