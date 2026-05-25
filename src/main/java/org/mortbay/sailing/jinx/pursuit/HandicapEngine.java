package org.mortbay.sailing.jinx.pursuit;

import java.util.List;
import java.util.Map;

import org.mortbay.sailing.jinx.model.Adjustment;
import org.mortbay.sailing.jinx.model.Boat;
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
 * values. Side effects (persistence, push to SailSys) live in callers.
 */
public interface HandicapEngine
{
    /**
     * Compute pursuit start times for the given race.
     *
     * @param boats   participating boats with their current TCFs
     * @param race    race with {@code targetElapsedMinutes} and {@code earliestStart} set
     * @return one entry per boat, ordered slowest start first
     */
    List<StartTime> computeStartTimes(List<Boat> boats, Race race);

    /**
     * Compute TCF adjustments from a race's results.
     *
     * @param boats   participating boats (current TCFs)
     * @param race    race that has just finished
     * @param results boatId → Result; missing boats are treated as DNC
     * @return one {@link Adjustment} per boat in {@code boats}, sum of deltas = 0
     */
    List<Adjustment> processResults(List<Boat> boats, Race race, Map<String, Result> results);
}
