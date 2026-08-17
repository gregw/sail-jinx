package org.mortbay.sailing.jinx.server;

import java.time.LocalTime;

import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.closeTo;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

/**
 * Unit tests for the pure calculations in {@link ApiServlet}. Everything else
 * the servlet does is a store read or write and is covered end-to-end by
 * {@link JinxApiIntegrationTest}.
 */
class ApiServletTest
{
    /**
     * A 1.000-TCF boat sails V₀ knots, so over 90 minutes it covers
     * 1.5 × V₀ nm. The course is sized to the slowest boat.
     */
    @Test
    void courseLengthIsSlowestTcfTimesV0TimesHours()
    {
        ApiServlet.CoursePlan plan = ApiServlet.computeCoursePlan(
            5.5, 90, false, null, null, 1.0);

        assertThat(plan.effectiveDurationMinutes(), equalTo(90));
        assertThat(plan.limitedBySunset(), is(false));
        assertThat(plan.courseLengthNm(), closeTo(8.3, 1e-9)); // 1.0 × 5.5 × 1.5 = 8.25 → 8.3
    }

    @Test
    void aSlowerFleetGetsAShorterCourse()
    {
        ApiServlet.CoursePlan fast = ApiServlet.computeCoursePlan(
            5.5, 90, false, null, null, 1.05);
        ApiServlet.CoursePlan slow = ApiServlet.computeCoursePlan(
            5.5, 90, false, null, null, 0.85);

        assertThat(slow.courseLengthNm() < fast.courseLengthNm(), is(true));
        assertThat(slow.courseLengthNm(), closeTo(7.0, 1e-9));  // 0.85 × 5.5 × 1.5 = 7.01
    }

    @Test
    void courseIsRoundedToATenthOfAMile()
    {
        // Nobody lays a course to four decimal places.
        ApiServlet.CoursePlan plan = ApiServlet.computeCoursePlan(
            6.0, 55, false, null, null, 0.9337);

        assertThat(plan.courseLengthNm(), closeTo(5.1, 1e-9)); // 5.1353 → 5.1
    }

    @Test
    void sunsetCapsTheDuration()
    {
        // Start 18:00, sunset 19:10: 70 minutes of daylight, not the 90 asked for.
        ApiServlet.CoursePlan plan = ApiServlet.computeCoursePlan(
            5.5, 90, true, LocalTime.of(18, 0), LocalTime.of(19, 10), 1.0);

        assertThat(plan.effectiveDurationMinutes(), equalTo(70));
        assertThat(plan.limitedBySunset(), is(true));
        assertThat(plan.courseLengthNm(), closeTo(6.4, 1e-9)); // 5.5 × 70/60 = 6.417
    }

    @Test
    void sunsetAfterTheRaceDoesNotCap()
    {
        ApiServlet.CoursePlan plan = ApiServlet.computeCoursePlan(
            5.5, 90, true, LocalTime.of(18, 0), LocalTime.of(20, 30), 1.0);

        assertThat(plan.effectiveDurationMinutes(), equalTo(90));
        assertThat(plan.limitedBySunset(), is(false));
    }

    @Test
    void sunsetBeforeTheStartClampsToZeroRatherThanGoingNegative()
    {
        // An out-of-season date where it is already dark at the earliest start.
        // The cap must still engage and say so, not silently pass the request
        // through or produce a negative course.
        ApiServlet.CoursePlan plan = ApiServlet.computeCoursePlan(
            5.5, 90, true, LocalTime.of(18, 0), LocalTime.of(17, 20), 1.0);

        assertThat(plan.effectiveDurationMinutes(), equalTo(0));
        assertThat(plan.limitedBySunset(), is(true));
        assertThat(plan.courseLengthNm(), closeTo(0.0, 1e-9));
    }

    @Test
    void sunsetCapIsIgnoredWhenNotConfigured()
    {
        ApiServlet.CoursePlan plan = ApiServlet.computeCoursePlan(
            5.5, 90, false, LocalTime.of(18, 0), LocalTime.of(18, 30), 1.0);

        assertThat(plan.effectiveDurationMinutes(), equalTo(90));
        assertThat(plan.limitedBySunset(), is(false));
    }
}
