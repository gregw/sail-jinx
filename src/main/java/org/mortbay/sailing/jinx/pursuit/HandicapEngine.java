package org.mortbay.sailing.jinx.pursuit;

import java.util.List;
import java.util.Map;

import org.mortbay.sailing.jinx.model.Adjustment;
import org.mortbay.sailing.jinx.model.Race;
import org.mortbay.sailing.jinx.model.Result;
import org.mortbay.sailing.jinx.model.StartTime;

/**
 * Pluggable handicap algorithm. The MYC pursuit algorithm is the first
 * implementation but the interface is deliberately general — a pure PHS
 * pass-through or a different reward distribution can implement the same
 * surface without touching the server or persistence layers.
 *
 * <p>Implementations are pure: they consume immutable inputs and return new
 * values. Persistence and every other side effect live in callers.
 */
public interface HandicapEngine
{
    /**
     * Compute pursuit start times for the given race.
     *
     * @param boats   participating boats with the TCF in force for this race
     * @param race    race with {@code targetElapsedMinutes} and {@code earliestStart} set
     * @return one entry per boat, in the order given — the caller sorts
     */
    List<StartTime> computeStartTimes(List<Competitor> boats, Race race);

    /**
     * Compute TCF adjustments from a race's results.
     *
     * @param boats   participating boats with the TCF in force for this race
     * @param race    race that has just finished
     * @param results boatId → Result; a boat with no result is frozen
     * @param nextRaceMinutes the expected duration of the race the new TCFs will be
     *                sailed in, which a time adjustment is measured against; null for
     *                the last race of a series, which falls back to its own
     * @return one {@link Adjustment} per boat in {@code boats}
     */
    List<Adjustment> processResults(List<Competitor> boats, Race race,
                                    Map<String, Result> results, Integer nextRaceMinutes);

    /** {@link #processResults(List, Race, Map, Integer)} with no next race. */
    default List<Adjustment> processResults(List<Competitor> boats, Race race,
                                            Map<String, Result> results)
    {
        return processResults(boats, race, results, null);
    }
}
