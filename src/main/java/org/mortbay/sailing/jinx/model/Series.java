package org.mortbay.sailing.jinx.model;

/**
 * A series of races — one season of one club event, e.g. "2026 Winter
 * Twilight". Created by hand in the app; there is no external system to import
 * one from.
 *
 * <p>Deliberately tiny. Everything that used to hang off a series here (race
 * counts, division counts, sub-series, the default handicap definition) was
 * SailSys's shape, not ours. Races carry their own {@code seriesId}, so the
 * count is a query; and there is exactly one handicap in this application —
 * the Jinx TCF.
 *
 * <p>{@code archived} hides a finished season from the default lists without
 * deleting it. Past seasons stay readable forever: they are the only record of
 * what a boat's TCF was at the time.
 */
public record Series(
    String id,
    String name,
    boolean archived)
{
}
