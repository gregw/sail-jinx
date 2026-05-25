package org.mortbay.sailing.jinx.model;

import java.time.LocalTime;

/**
 * One boat's published start time for a race.
 *
 * <p>{@code expectedElapsed} is the computed τ (tau) value — what we believe this
 * boat will take to finish at the configured target elapsed time. The published
 * {@code startTime} is rounded to the minute; rounding error is absorbed into the
 * TCF on the next adjustment.
 */
public record StartTime(
    String boatId,
    double tcf,
    double expectedElapsedMinutes,
    LocalTime startTime)
{
}
