package org.mortbay.sailing.jinx.model;

/**
 * Per-boat per-race output of the handicap engine: the race page's Adjustment column,
 * the finish sheet, and the audit log.
 *
 * <p>Across one pass of the engine, {@code netAdjustmentMinutes} sums to zero. Frozen
 * boats — DSQ, ABN — get a row with zero deltas and {@code oldTcf == newTcf}.
 * There is no fleet-wide anchor: the next race's start times re-anchor the slowest boat.
 */
public record Adjustment(
    String boatId,
    Integer finishPosition,        // null for non-finishers
    double penaltyMinutes,         // penalty for a place on the ladder, else 0
    double rewardMinutes,          // share of pool returned
    double netAdjustmentMinutes,   // penalty - reward (wiki §6.4 — the Δs that drives the TCF formula)
    double oldTcf,
    double newTcf)
{
}
