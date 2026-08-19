package org.mortbay.sailing.jinx.model;

import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The boats entered for a series, and the terms each one enters on. Stored as
 * {@code data/store/roster/{seriesId}.json}.
 *
 * <p>The roster seeds the first race's entrants; after that each race inherits its TCFs
 * from the previous race's handicap processing. Adding a boat mid-season does not
 * retro-fit it into races already sailed.
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

    /** The entry for the given boat, if it is on this roster. */
    public Optional<Entry> forBoat(String boatId)
    {
        if (boatId == null)
            return Optional.empty();
        return entries.stream().filter(e -> boatId.equals(e.boatId())).findFirst();
    }

    /**
     * One boat's entry into the series — the terms it sails on, which belong to the
     * entry rather than to the boat.
     *
     * <p>{@code startingTcf} is the handicap it begins the season with; from race 2 the
     * TCF in force is whatever the previous race's processing produced, held on the
     * race's {@link Entrant}.
     *
     * <p>{@code division} and {@code spinnaker} are also per-entry: the same hull can be
     * in Division 1 one season and Division 2 the next, and can enter with or without a
     * kite. Both are nullable — plenty of series use neither.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Entry(
        String boatId,
        double startingTcf,
        String division,
        Spinnaker spinnaker)
    {
        public Entry
        {
            startingTcf = Tcf.round(startingTcf);
        }

        /** Entry on the given handicap with no division or spinnaker recorded. */
        public Entry(String boatId, double startingTcf)
        {
            this(boatId, startingTcf, null, null);
        }
    }
}
