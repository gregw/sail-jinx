package org.mortbay.sailing.jinx.server;

import java.time.LocalTime;

import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

/**
 * Unit tests for the pure calculations in {@link ApiServlet}. Everything else the servlet
 * does is a store read or write and is covered end-to-end by {@link JinxApiIntegrationTest}.
 */
class ApiServletTest
{
    @Test
    void aTargetThatFitsBeforeSunsetIsLeftAlone()
    {
        assertThat(ApiServlet.capBySunset(90, LocalTime.of(18, 0), LocalTime.of(20, 30)),
            equalTo(90));
    }

    @Test
    void aTargetRunningPastSunsetIsCappedToTheDaylightLeft()
    {
        // Start 18:00, sunset 19:10 — seventy minutes, not the ninety asked for.
        assertThat(ApiServlet.capBySunset(90, LocalTime.of(18, 0), LocalTime.of(19, 10)),
            equalTo(70));
    }

    @Test
    void theCapIsExactAtTheBoundary()
    {
        assertThat(ApiServlet.capBySunset(90, LocalTime.of(18, 0), LocalTime.of(19, 30)),
            equalTo(90));
    }

    @Test
    void sunsetBeforeTheStartClampsToZeroRatherThanGoingNegative()
    {
        // An out-of-season date where it is already dark at the earliest start. The cap
        // still engages rather than silently passing the request through.
        assertThat(ApiServlet.capBySunset(90, LocalTime.of(18, 0), LocalTime.of(17, 20)),
            equalTo(0));
    }

    @Test
    void noSunsetMeansNoCap()
    {
        // Either the series does not ask for the cap, or the date is unknown.
        assertThat(ApiServlet.capBySunset(90, LocalTime.of(18, 0), null), equalTo(90));
        assertThat(ApiServlet.capBySunset(90, null, LocalTime.of(19, 0)), equalTo(90));
    }
}
