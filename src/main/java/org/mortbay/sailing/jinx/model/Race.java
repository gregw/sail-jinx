package org.mortbay.sailing.jinx.model;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * A single race in a series.
 *
 * <p>{@code targetElapsedMinutes} (t_target_elapsed) and {@code earliestStart}
 * (t_earliest_start) are the two per-race inputs the pursuit algorithm needs —
 * see {@code wiki/Jinx-Handicaps.md} §3.2. {@code courseLengthNm} is what the
 * RO actually sets on the water; it is derived from the target duration by the
 * course calculator (V₀ and the sunset cap) but stays editable, because the
 * course you can actually lay depends on the breeze.
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
    Double courseLengthNm,
    boolean abandoned)
{
}
