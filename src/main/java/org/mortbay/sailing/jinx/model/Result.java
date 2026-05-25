package org.mortbay.sailing.jinx.model;

import java.time.Duration;
import java.time.LocalTime;

/**
 * One boat's race result, as captured by the race officer.
 *
 * <p>{@code actualStart} is editable independently of the published start time so
 * an OCS restart can be recorded by simply moving this later. {@code finish} is the
 * wall-clock finish time. Elapsed time is derived from {@code finish - actualStart}
 * by the algorithm — never persisted, never editable.
 *
 * <p>{@code penaltyMinutes} is the optional protest/umpire scoring penalty applied
 * before the algorithm runs. It is additive to the boat's elapsed time.
 */
public record Result(
    String boatId,
    FinishStatus status,
    LocalTime actualStart,
    LocalTime finish,
    Double penaltyMinutes)
{
    /**
     * Derived elapsed time, or null when either timestamp is missing. The algorithm
     * does its own status-aware time assignment in {@code HandicapEngine}; this is
     * only for display.
     */
    public Duration elapsed()
    {
        if (actualStart == null || finish == null)
            return null;
        return Duration.between(actualStart, finish);
    }
}
