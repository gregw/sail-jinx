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
    List<TcfEntry> tcfs)
{
    public enum Source { PROCESS_HANDICAPS, MANUAL_EDIT }

    public record TcfEntry(String boatId, double value, int spinnakerType)
    {
    }
}
