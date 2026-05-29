package org.mortbay.sailing.jinx.model;

import java.time.Instant;

/**
 * Calibration of the SailSys TCF → start-offset conversion. Derived by running
 * the {@code /races/{id}/calibrate} probe, which forces every division to a
 * known course length and start time and reads back the per-boat staggered
 * starts SailSys computes.
 *
 * <p>SailSys assumes a boat's predicted speed is {@code tcf × v0Knots}, so over
 * a course of length {@code D} (nm) two boats finish at the same time iff their
 * start offsets satisfy
 * <pre>
 *   start_offset = (D / v0Knots) × (1/TCF_min − 1/TCF) × 60   [minutes]
 * </pre>
 * Solving across the slowest and fastest boats present in the probe gives
 * <pre>
 *   v0Knots = (D × 60 / Δt_seconds) × (1/slowestTcf − 1/fastestTcf) × 60
 *           = D / (Δt_hours) × (1/slowestTcf − 1/fastestTcf)
 * </pre>
 * The provenance fields ({@code courseLengthNm}, the two TCF/offset pairs) let
 * the UI re-display the derivation after a reload, and let a future schema
 * upgrade re-derive {@code v0Knots} if the formula changes.
 *
 * <p>The handicap engine uses {@code v0Knots} to translate each boat's
 * {@code netAdjustmentMinutes} (Δs) into a new TCF.
 */
public record Calibration(
    double v0Knots,
    double courseLengthNm,
    double slowestTcf,
    long slowestStartOffsetSeconds,
    double fastestTcf,
    long fastestStartOffsetSeconds,
    Instant computedAt)
{
}
