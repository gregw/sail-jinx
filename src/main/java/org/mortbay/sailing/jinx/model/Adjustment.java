package org.mortbay.sailing.jinx.model;

/**
 * Per-boat per-race output of the handicap engine. Surfaced to the UI's
 * Processing Preview screen and to the audit log.
 *
 * <p>By the algorithm's construction the sum of {@code netAdjustmentMinutes} across
 * participating boats is zero (the penalty pool is exactly distributed). DSQ / DNC
 * / DNS boats produce an {@code Adjustment} with zero deltas and {@code oldTcf == newTcf}
 * so the audit log still shows them.
 *
 * <p>There is no fleet-wide anchor field — the next race's start-time processing
 * pass over the updated TCFs is what re-anchors the slowest boat at the configured
 * earliest-start time, so the per-boat Δs is the complete handicap-side answer.
 */
public record Adjustment(
    String boatId,
    Integer finishPosition,        // null for non-finishers
    double penaltyMinutes,         // fixed penalty for top finishers
    double rewardMinutes,          // share of pool returned
    double netAdjustmentMinutes,   // penalty - reward (wiki §6.4 — the Δs that drives the TCF formula)
    double oldTcf,
    double newTcf)
{
}
