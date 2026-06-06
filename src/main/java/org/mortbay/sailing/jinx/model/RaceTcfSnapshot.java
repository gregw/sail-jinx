package org.mortbay.sailing.jinx.model;

import java.time.Instant;
import java.util.List;

/**
 * Local per-race snapshot of the TCFs in effect for a given race. SailSys
 * only stores the latest TCF for each boat, so without a local copy there
 * is no audit trail of what a boat's TCF was on race 4 once race 5 has been
 * processed. Snapshots are written from two sources:
 *
 * <ul>
 *   <li>{@link Source#PROCESS_HANDICAPS} — Save Handicaps on race N writes
 *       race N+1's snapshot from {@link Adjustment#newTcf()}. No SailSys
 *       push: the race-N+1 page surfaces a banner offering Push / Reset.</li>
 *   <li>{@link Source#MANUAL_EDIT} — RO edits a TCF in the entrants table
 *       and clicks Save TCFs. Snapshot is written then immediately pushed to
 *       SailSys in one bulk PUT.</li>
 * </ul>
 */
public record RaceTcfSnapshot(
    String raceId,
    Instant savedAt,
    Source source,
    String sourceRaceId,
    Integer sourceRaceNumber,
    List<TcfEntry> tcfs)
{
    public enum Source { PROCESS_HANDICAPS, MANUAL_EDIT }

    /**
     * A boat's TCF in this snapshot. {@code value} is always quantised to
     * 4 decimal places using truncation (SailSys's observed behaviour:
     * pushing {@code 0.9287868} to its bulk-handicap endpoint stores
     * {@code 0.9287}, not {@code 0.9288}). Storing full-precision doubles
     * would make the snapshot perpetually diverge from what SailSys echoes
     * back, so the mismatch banner would never clear. The compact
     * constructor enforces this on every construction path: Process
     * Handicaps (engine's full-precision newTcf), manual edit (UI's
     * 4-decimal input), and JSON deserialisation (legacy on-disk files
     * with full-precision values get truncated on read).
     */
    public record TcfEntry(String boatId, double value, int spinnakerType)
    {
        public TcfEntry
        {
            value = java.math.BigDecimal.valueOf(value)
                .setScale(4, java.math.RoundingMode.DOWN)
                .doubleValue();
        }
    }
}
