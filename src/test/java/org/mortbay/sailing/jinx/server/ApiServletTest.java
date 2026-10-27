package org.mortbay.sailing.jinx.server;

import java.time.LocalTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.mortbay.sailing.jinx.model.Entrant;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

/**
 * Unit tests for the pure calculations in {@link ApiServlet}. Everything else the servlet
 * does is a store read or write and is covered end-to-end by {@link JinxApiIntegrationTest}.
 */
class ApiServletTest
{
    /** Every boat on one TCF: the slowest boat is the median boat. */
    private static final List<Double> ONE_DESIGN = List.of(1.0, 1.0, 1.0);

    @Test
    void aTargetThatFitsBeforeSunsetIsLeftAlone()
    {
        assertThat(ApiServlet.capBySunset(90, LocalTime.of(18, 0), LocalTime.of(20, 30), 0, ONE_DESIGN),
            equalTo(90));
    }

    @Test
    void aTargetRunningPastSunsetIsCappedToTheDaylightLeft()
    {
        // Start 18:00, sunset 19:10 — seventy minutes, not the ninety asked for.
        assertThat(ApiServlet.capBySunset(90, LocalTime.of(18, 0), LocalTime.of(19, 10), 0, ONE_DESIGN),
            equalTo(70));
    }

    @Test
    void theCapIsExactAtTheBoundary()
    {
        assertThat(ApiServlet.capBySunset(90, LocalTime.of(18, 0), LocalTime.of(19, 30), 0, ONE_DESIGN),
            equalTo(90));
    }

    @Test
    void sunsetBeforeTheStartClampsToZeroRatherThanGoingNegative()
    {
        // An out-of-season date where it is already dark at the earliest start. The cap
        // still engages rather than silently passing the request through.
        assertThat(ApiServlet.capBySunset(90, LocalTime.of(18, 0), LocalTime.of(17, 20), 0, ONE_DESIGN),
            equalTo(0));
    }

    @Test
    void theSlowestBoatIsHomeBySunsetNotTheMedianBoat()
    {
        // Start 18:00, sunset 19:07: 67 minutes of daylight. The slowest boat (0.8)
        // takes 0.9/0.8 of the median boat's time, so the target is 67 × 0.8/0.9 =
        // 59.6, rounded down. Capping the target to 67 brought the fleet home at 19:15.
        assertThat(ApiServlet.capBySunset(90, LocalTime.of(18, 0), LocalTime.of(19, 7), 0,
            List.of(1.0, 0.9, 0.8)), equalTo(59));
    }

    @Test
    void aTargetWhoseSlowestBoatFitsBeforeSunsetIsLeftAlone()
    {
        // 59 × 0.9/0.8 = 66.4 minutes for the slowest boat, inside the 67 available.
        assertThat(ApiServlet.capBySunset(59, LocalTime.of(18, 0), LocalTime.of(19, 7), 0,
            List.of(1.0, 0.9, 0.8)), equalTo(59));
    }

    @Test
    void theFleetMayBeExpectedHomeMinutesAfterSunset()
    {
        // 19:07 sunset and eight minutes after it: 75 minutes from an 18:00 start, so
        // 75 × 0.8/0.9 = 66.7, rounded down. The slowest boat is home at 19:14.
        assertThat(ApiServlet.capBySunset(90, LocalTime.of(18, 0), LocalTime.of(19, 7), 8,
            List.of(1.0, 0.9, 0.8)), equalTo(66));
    }

    @Test
    void aNegativeAllowanceBringsTheFleetHomeBeforeSunset()
    {
        assertThat(ApiServlet.capBySunset(90, LocalTime.of(18, 0), LocalTime.of(19, 10), -10,
            ONE_DESIGN), equalTo(60));
    }

    /**
     * The cap is the series fleet's, like everything else about a series boat's start:
     * a slow casual starts before the earliest start and is expected home with everyone
     * else, so it must not shorten the race.
     */
    @Test
    void theSunsetCapIsMeasuredOnTheSeriesFleetAlone()
    {
        List<Entrant> entrants = List.of(
            entrant("a", 1.0, Entrant.EntryType.ROSTER),
            entrant("b", 0.9, Entrant.EntryType.ROSTER),
            entrant("c", 0.8, Entrant.EntryType.ROSTER),
            entrant("casual", 0.6, Entrant.EntryType.CASUAL),
            Entrant.oneOff("one-off-1", "Visitor", "V1", 0.5));
        assertThat(ApiServlet.seriesTcfs(entrants), equalTo(List.of(1.0, 0.9, 0.8)));
    }

    @Test
    void aFleetOfOnlyCasualsIsCappedOnItself()
    {
        List<Entrant> entrants = List.of(
            entrant("casual", 0.6, Entrant.EntryType.CASUAL),
            Entrant.oneOff("one-off-1", "Visitor", "V1", 0.5));
        assertThat(ApiServlet.seriesTcfs(entrants), equalTo(List.of(0.6, 0.5)));
    }

    private static Entrant entrant(String id, double tcf, Entrant.EntryType type)
    {
        return new Entrant(id, id, id, null, null, null, tcf, type);
    }

    @Test
    void noSunsetMeansNoCap()
    {
        // Either the series does not ask for the cap, or the date is unknown.
        assertThat(ApiServlet.capBySunset(90, LocalTime.of(18, 0), null, 0, ONE_DESIGN), equalTo(90));
        assertThat(ApiServlet.capBySunset(90, null, LocalTime.of(19, 0), 0, ONE_DESIGN), equalTo(90));
    }
}
