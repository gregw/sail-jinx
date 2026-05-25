package org.mortbay.sailing.jinx.model;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * A single race in the series. {@code id} is the SailSys integer raceId (as a string).
 *
 * <p>{@code targetElapsedMinutes} and {@code earliestStart} are the per-race inputs
 * the algorithm needs to compute pursuit start times. They are filled in once the RO
 * selects them for the race; before that the race is in {@link RaceStatus#SCHEDULED}.
 */
public record Race(
    String id,
    int number,
    String name,
    LocalDate date,
    Integer targetElapsedMinutes,
    LocalTime earliestStart,
    RaceStatus status)
{
}
