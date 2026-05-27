package org.mortbay.sailing.jinx.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * A series of races at a SailSys club. The race officer picks one of these on
 * the Series tab; the chosen series scopes the entries and start-time work
 * downstream.
 *
 * <p>Field set matches the SailSys {@code POST /series/all} response. Unknown
 * fields are tolerated because SailSys returns many incidental ones we have no
 * use for (logos, sponsor images, compliance flags, etc.).
 *
 * <p>{@code status}: 0 = upcoming, 1 = active, 2 = completed (decoded from
 * status integers observed in the production HAR capture).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Series(
    int id,
    String name,
    int status,
    int racesCount,
    int divisionsCount,
    int subSeriesCount,
    int defaultHandicap)
{
    public String statusLabel()
    {
        return switch (status)
        {
            case 0 -> "upcoming";
            case 1 -> "active";
            case 2 -> "completed";
            default -> "status=" + status;
        };
    }
}
