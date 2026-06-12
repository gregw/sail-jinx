package org.mortbay.sailing.jinx.pursuit;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.closeTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Sunset is the cutoff that {@code limitBySunset} caps the race duration
 * against, so the value has to be both correct and DST-aware (the originating
 * MYC Twilight series runs through summer, when Sydney is AEDT/UTC+11).
 *
 * <p>Expected values are published Sydney (Observatory Hill, −33.8688,
 * 151.2093) sunsets; the standard sunrise/sunset equation lands within a
 * couple of minutes, so the assertions use a ±6 min window — tight enough to
 * catch a UTC/local, sign, or DST mistake (those miss by 30–60+ min) but
 * forgiving of the algorithm's inherent approximation.
 */
class SolarTimesTest
{
    private static final double SYD_LAT = -33.8688;
    private static final double SYD_LNG = 151.2093;
    private static final ZoneId SYDNEY = ZoneId.of("Australia/Sydney");

    private static int minutesOfDay(LocalTime t)
    {
        return t.getHour() * 60 + t.getMinute();
    }

    @Test
    void sydneyWinterSolsticeSunsetIsLateAfternoonAest()
    {
        // 2026-06-21: AEST (UTC+10), published sunset ~16:54.
        LocalTime sunset = SolarTimes.sunsetLocal(SYD_LAT, SYD_LNG,
            LocalDate.of(2026, 6, 21), SYDNEY);
        assertThat(sunset, is(notNullValue()));
        assertThat((double) minutesOfDay(sunset), closeTo(16 * 60 + 54, 6));
    }

    @Test
    void sydneySummerSolsticeSunsetIsAfterEightPmAedt()
    {
        // 2026-12-21: AEDT (UTC+11), published sunset ~20:05. If DST were
        // mishandled this would land near 19:05 (an hour off) and fail.
        LocalTime sunset = SolarTimes.sunsetLocal(SYD_LAT, SYD_LNG,
            LocalDate.of(2026, 12, 21), SYDNEY);
        assertThat(sunset, is(notNullValue()));
        assertThat((double) minutesOfDay(sunset), closeTo(20 * 60 + 5, 6));
    }

    @Test
    void equatorEquinoxSunsetIsAroundSixPmUtcAtPrimeMeridian()
    {
        // (0,0) on the March equinox: solar noon ≈ 12:00 UTC, sunset ≈ 18:0x.
        LocalTime sunset = SolarTimes.sunsetLocal(0.0, 0.0,
            LocalDate.of(2026, 3, 20), ZoneOffset.UTC);
        assertThat(sunset, is(notNullValue()));
        assertThat((double) minutesOfDay(sunset), closeTo(18 * 60 + 5, 8));
    }
}
