package org.mortbay.sailing.jinx.model;

import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

class RaceTcfSnapshotTest
{
    @Test
    void tcfEntryTruncatesToFourDecimals()
    {
        // SailSys truncates pushed TCFs to 4 decimals on store: pushing
        // 0.9287868 results in 0.9287 echoed back. The snapshot must
        // truncate (not round) so reading it back compares equal.
        assertThat(new RaceTcfSnapshot.TcfEntry("b", 0.9287868830776679, 1).value(),
            equalTo(0.9287));
        assertThat(new RaceTcfSnapshot.TcfEntry("b", 1.0992626086373853, 1).value(),
            equalTo(1.0992));
    }

    @Test
    void tcfEntryLeavesAlreadyShortValuesUnchanged()
    {
        assertThat(new RaceTcfSnapshot.TcfEntry("b", 1.0, 1).value(), equalTo(1.0));
        assertThat(new RaceTcfSnapshot.TcfEntry("b", 0.9999, 1).value(), equalTo(0.9999));
        assertThat(new RaceTcfSnapshot.TcfEntry("b", 0.79, 1).value(), equalTo(0.79));
    }
}
