package org.mortbay.sailing.jinx.model;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * A single race in a series.
 *
 * <p>{@code targetElapsedMinutes} (t_target_elapsed) and {@code earliestStart}
 * (t_earliest_start) are the only per-race inputs the pursuit algorithm needs —
 * see {@code wiki/Jinx-Handicaps.md} §3.2. Between them they decide every boat's gun.
 *
 * <p>The course length is deliberately not here. What the RO lays on the water is a
 * judgement made from the breeze on the night; the app's business is how long the race
 * is meant to take, and recording a course length it cannot verify would be a second,
 * quietly wrong answer to that question.
 *
 * <p>There is no status field. The race lifecycle is <em>derived</em>: a race
 * is still "current" — live-timing buttons on, times editable — until its
 * handicaps have been processed and saved, at which point the saved adjustments
 * lock it. Unlocking is deleting those adjustments. A stored status would be a
 * second source of truth for something the data already answers, and the
 * SailSys-era version of this field was sticky in exactly that way.
 */
public record Race(
    String id,
    String seriesId,
    int number,
    String name,
    LocalDate date,
    LocalTime earliestStart,
    Integer targetElapsedMinutes,
    boolean abandoned)
{
}
