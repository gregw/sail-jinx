package org.mortbay.sailing.jinx.model;

import java.util.List;

/**
 * The boats entered for a series, with the TCF each one starts the season on.
 * Stored as {@code data/store/roster/{seriesId}.json}.
 *
 * <p>The roster seeds the first race's entrants; after that each race inherits
 * its TCFs from the previous race's handicap processing. Adding a boat to the
 * roster mid-season does not retro-fit it into races already sailed.
 */
public record Roster(
    String seriesId,
    List<Entry> entries)
{
    public Roster
    {
        if (entries == null)
            entries = List.of();
    }

    /** One registered boat's entry into the series. */
    public record Entry(
        String boatId,
        double startingTcf)
    {
        public Entry
        {
            startingTcf = Tcf.round(startingTcf);
        }
    }
}
