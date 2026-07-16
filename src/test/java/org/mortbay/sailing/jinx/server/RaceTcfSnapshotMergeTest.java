package org.mortbay.sailing.jinx.server;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.mortbay.sailing.jinx.model.RaceTcfSnapshot;
import org.mortbay.sailing.jinx.model.RaceTcfSnapshot.Source;
import org.mortbay.sailing.jinx.model.RaceTcfSnapshot.TcfEntry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * A manual TCF save must MERGE into any existing snapshot for the race, not
 * replace it. The failure this guards against: a race inherits a
 * PROCESS_HANDICAPS snapshot holding EVERY boat's next-race TCF; the RO
 * edits one boat and saves; a replace-style write would throw away all the
 * other boats' queued adjustments, so the subsequent push to SailSys would
 * push just the one edit and silently lose the previous race's handicap
 * processing.
 */
public class RaceTcfSnapshotMergeTest
{
    private static final Instant NOW = Instant.parse("2026-07-11T10:00:00Z");

    private static RaceTcfSnapshot processed()
    {
        return new RaceTcfSnapshot("101", Instant.parse("2026-07-10T09:00:00Z"),
            Source.PROCESS_HANDICAPS, "100", 4,
            List.of(
                new TcfEntry("1", 0.9000, 1),
                new TcfEntry("2", 0.9500, 2),
                new TcfEntry("3", 1.0100, 1)));
    }

    private static double tcfOf(RaceTcfSnapshot snap, String boatId)
    {
        return snap.tcfs().stream()
            .filter(t -> t.boatId().equals(boatId))
            .findFirst().orElseThrow().value();
    }

    @Test
    public void mergeWithoutExistingSnapshotIsManualEdit()
    {
        RaceTcfSnapshot merged = ApiServlet.mergeTcfSnapshot(null, "101", NOW,
            List.of(new TcfEntry("2", 0.9700, 2)));

        assertEquals("101", merged.raceId());
        assertEquals(NOW, merged.savedAt());
        assertEquals(Source.MANUAL_EDIT, merged.source());
        assertNull(merged.sourceRaceId());
        assertNull(merged.sourceRaceNumber());
        assertEquals(1, merged.tcfs().size());
        assertEquals(0.9700, tcfOf(merged, "2"));
    }

    @Test
    public void mergePreservesUneditedBoats()
    {
        RaceTcfSnapshot merged = ApiServlet.mergeTcfSnapshot(processed(), "101", NOW,
            List.of(new TcfEntry("2", 0.9700, 2)));

        assertEquals(3, merged.tcfs().size());
        assertEquals(0.9000, tcfOf(merged, "1"));
        assertEquals(0.9700, tcfOf(merged, "2"));
        assertEquals(1.0100, tcfOf(merged, "3"));
    }

    @Test
    public void mergePreservesSourceAndProvenance()
    {
        RaceTcfSnapshot merged = ApiServlet.mergeTcfSnapshot(processed(), "101", NOW,
            List.of(new TcfEntry("2", 0.9700, 2)));

        // The snapshot is still (mostly) the previous race's handicap
        // processing — the UI's "TCFs updated by handicap processing after
        // race N" explanation must survive a rider edit. Only savedAt moves.
        assertEquals(Source.PROCESS_HANDICAPS, merged.source());
        assertEquals("100", merged.sourceRaceId());
        assertEquals(4, merged.sourceRaceNumber());
        assertEquals(NOW, merged.savedAt());
    }

    @Test
    public void mergeAppendsBoatsNotInExistingSnapshot()
    {
        RaceTcfSnapshot merged = ApiServlet.mergeTcfSnapshot(processed(), "101", NOW,
            List.of(new TcfEntry("9", 0.8800, 1)));

        assertEquals(4, merged.tcfs().size());
        assertEquals(0.8800, tcfOf(merged, "9"));
        assertEquals(0.9500, tcfOf(merged, "2"));
    }

    @Test
    public void mergeUpdatesSpinnakerTypeForEditedBoat()
    {
        RaceTcfSnapshot merged = ApiServlet.mergeTcfSnapshot(processed(), "101", NOW,
            List.of(new TcfEntry("1", 0.9100, 2)));

        TcfEntry one = merged.tcfs().stream()
            .filter(t -> t.boatId().equals("1")).findFirst().orElseThrow();
        assertEquals(2, one.spinnakerType());
    }
}
