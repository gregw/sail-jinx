package org.mortbay.sailing.jinx.model;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The full entrant list for one race, stored as
 * {@code data/store/entrants/{raceId}.json}.
 *
 * <p>This file is the per-race TCF history. Each {@link Entrant} carries the
 * TCF in force for that boat in that race, so processing race 5 cannot disturb
 * what race 4 says — the problem that forced a separate snapshot file back when
 * SailSys owned the handicaps and only ever kept the latest value.
 *
 * <p>The provenance fields say where these TCFs came from, which is what the
 * race page shows in its "handicaps after race N" line.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RaceEntrants(
    String raceId,
    Instant savedAt,
    TcfSource tcfSource,
    String sourceRaceId,
    Integer sourceRaceNumber,
    List<Entrant> entrants)
{
    /** Where this race's TCFs came from. */
    public enum TcfSource
    {
        /** Seeded from the series roster — the first race of a series. */
        ROSTER,
        /** Produced by processing the handicaps of {@code sourceRaceId}. */
        CARRIED_FORWARD,
        /** An admin typed at least one of them on the race page. */
        MANUAL_EDIT
    }

    public RaceEntrants
    {
        if (entrants == null)
            entrants = List.of();
    }

    /** The entrant for the given boat, if it is in this race. */
    public Optional<Entrant> forBoat(String boatId)
    {
        if (boatId == null)
            return Optional.empty();
        return entrants.stream().filter(e -> boatId.equals(e.boatId())).findFirst();
    }

    /**
     * A copy with the given boats' TCFs replaced and the source marked
     * {@link TcfSource#MANUAL_EDIT}. Entrants not named in {@code newTcfs} are
     * untouched — an edit to one boat must never drop the TCFs carried forward
     * for the rest of the fleet.
     */
    public RaceEntrants withTcfs(java.util.Map<String, Double> newTcfs, Instant now)
    {
        List<Entrant> updated = entrants.stream()
            .map(e -> {
                Double v = (e.boatId() == null) ? null : newTcfs.get(e.boatId());
                return (v == null) ? e : new Entrant(e.boatId(), e.sailNumber(), e.name(),
                    e.division(), e.spinnaker(), v, e.entryType());
            })
            .toList();
        return new RaceEntrants(raceId, now, TcfSource.MANUAL_EDIT,
            sourceRaceId, sourceRaceNumber, updated);
    }
}
