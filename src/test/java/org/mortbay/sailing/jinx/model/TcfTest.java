package org.mortbay.sailing.jinx.model;

import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

class TcfTest
{
    @Test
    void roundsToFourDecimals()
    {
        assertThat(Tcf.round(0.9287868), equalTo(0.9288));
        assertThat(Tcf.round(1.04503), equalTo(1.0450));
    }

    @Test
    void roundsHalfUpRatherThanTruncating()
    {
        // Truncation is what the old SailSys-facing code did, because SailSys
        // truncated. On its own a truncating handicap system drifts downward
        // every time it is processed, which over a season is a real bias.
        assertThat(Tcf.round(0.92885), equalTo(0.9289));
        assertThat(Tcf.round(0.99999), equalTo(1.0));
    }

    @Test
    void alreadyRoundValuesAreUnchanged()
    {
        assertThat(Tcf.round(1.0), equalTo(1.0));
        assertThat(Tcf.round(0.8821), equalTo(0.8821));
    }

    @Test
    void negativeAndZeroPassThroughWithoutBlowingUp()
    {
        // Not meaningful handicaps, but the engine must never throw on one:
        // a bad TCF should surface as a visibly silly number, not a 500.
        assertThat(Tcf.round(0.0), equalTo(0.0));
        assertThat(Tcf.round(-0.12345), equalTo(-0.1235));
    }

    @Test
    void nonFiniteValuesAreLeftAlone()
    {
        // A degenerate handicap calculation can produce these. Rounding them is
        // meaningless; BigDecimal would throw. Let them through so the caller
        // sees the actual problem rather than a rounding stack trace.
        assertThat(Double.isNaN(Tcf.round(Double.NaN)), is(true));
        assertThat(Tcf.round(Double.POSITIVE_INFINITY), equalTo(Double.POSITIVE_INFINITY));
    }

    @Test
    void formatsToFourDecimalsForDisplayAndTranscription()
    {
        // The report and the UI must show a stable four-decimal string — a bare
        // Double.toString would render 1.0450 as "1.045".
        assertThat(Tcf.format(1.0450), equalTo("1.0450"));
        assertThat(Tcf.format(1.0), equalTo("1.0000"));
        assertThat(Tcf.format(0.9287868), equalTo("0.9288"));
    }
}