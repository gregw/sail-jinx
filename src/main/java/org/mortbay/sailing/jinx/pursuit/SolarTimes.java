package org.mortbay.sailing.jinx.pursuit;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * Local sunset computation via the standard sunrise/sunset equation, used to
 * cap a pursuit race's duration so the slowest boat is expected to finish by
 * sunset (the {@code limitBySunset} feature). Self-contained — no external API
 * — and DST-correct because the final UTC instant is rendered in the caller's
 * {@link ZoneId} (Sydney swings between AEST/UTC+10 and AEDT/UTC+11 across the
 * twilight season).
 *
 * <p>Algorithm: <a href="https://en.wikipedia.org/wiki/Sunrise_equation">the
 * Wikipedia sunrise equation</a>, using the −0.833° geometric-sunset altitude
 * (accounts for the solar disc radius and average atmospheric refraction).
 * Accurate to ~1–2 minutes at temperate latitudes.
 */
public final class SolarTimes
{
    private SolarTimes() {}

    /** Sun-centre altitude at apparent sunset: −50′ (disc radius + refraction). */
    private static final double SUNSET_ALTITUDE_DEG = -0.833;
    private static final double OBLIQUITY_DEG = 23.4397;

    /**
     * Local wall-clock sunset for the given location and date, or {@code null}
     * when the sun does not set that day (polar summer) or never rises (polar
     * winter) — neither occurs at the latitudes this serves.
     *
     * @param latDeg latitude in degrees, north positive
     * @param lngDeg longitude in degrees, east positive
     * @param date   the local calendar date
     * @param zone   zone used to render the result (DST handled here)
     */
    public static LocalTime sunsetLocal(double latDeg, double lngDeg, LocalDate date, ZoneId zone)
    {
        // West longitude is positive in the classic formulation.
        double lw = -lngDeg;
        double latRad = Math.toRadians(latDeg);

        // Julian date at the start of the local date (UTC reference is fine —
        // n is rounded to a whole day and the result is exact to the minute).
        double jd = date.atStartOfDay(ZoneOffset.UTC).toEpochSecond() / 86400.0 + 2440587.5;

        // Current Julian day (days since 2000-01-01 12:00, leap-second nudge).
        double n = Math.ceil(jd - 2451545.0 + 0.0008);

        // Mean solar time at this longitude.
        double jStar = n + lw / 360.0;

        // Solar mean anomaly.
        double mDeg = (357.5291 + 0.98560028 * jStar) % 360.0;
        double mRad = Math.toRadians(mDeg);

        // Equation of the centre.
        double cDeg = 1.9148 * Math.sin(mRad)
            + 0.0200 * Math.sin(2 * mRad)
            + 0.0003 * Math.sin(3 * mRad);

        // Ecliptic longitude.
        double lambdaDeg = (mDeg + cDeg + 180.0 + 102.9372) % 360.0;
        double lambdaRad = Math.toRadians(lambdaDeg);

        // Solar transit (Julian date of local solar noon).
        double jTransit = 2451545.0 + jStar
            + 0.0053 * Math.sin(mRad)
            - 0.0069 * Math.sin(2 * lambdaRad);

        // Sun's declination.
        double sinDelta = Math.sin(lambdaRad) * Math.sin(Math.toRadians(OBLIQUITY_DEG));
        double cosDelta = Math.cos(Math.asin(sinDelta));

        // Hour angle at sunset.
        double cosOmega = (Math.sin(Math.toRadians(SUNSET_ALTITUDE_DEG)) - Math.sin(latRad) * sinDelta)
            / (Math.cos(latRad) * cosDelta);
        if (cosOmega < -1.0 || cosOmega > 1.0)
            return null; // sun never sets / never rises this day

        double omegaDeg = Math.toDegrees(Math.acos(cosOmega));

        // Julian date of sunset, then convert to a UTC instant and render local.
        double jSet = jTransit + omegaDeg / 360.0;
        long epochSeconds = Math.round((jSet - 2440587.5) * 86400.0);
        return Instant.ofEpochSecond(epochSeconds).atZone(zone).toLocalTime();
    }
}
