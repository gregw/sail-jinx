package org.mortbay.sailing.jinx.model;

import java.time.LocalTime;

/**
 * One boat's published start time for a race.
 *
 * <p>{@code expectedElapsedMinutes} is τ — how long this boat is expected to take
 * when the median boat takes the race's expected duration. {@code startTime} is
 * rounded to the nearest minute.
 */
public record StartTime(
    String boatId,
    double tcf,
    double expectedElapsedMinutes,
    LocalTime startTime)
{
}
