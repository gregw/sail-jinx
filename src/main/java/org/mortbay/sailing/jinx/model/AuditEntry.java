package org.mortbay.sailing.jinx.model;

import java.time.Instant;
import java.util.List;

/**
 * Audit record of a processed race. Written by {@code /api/races/{id}/process}
 * and never modified — push success/failure is recorded in a follow-up entry.
 *
 * <p>{@code action} is a short tag like {@code "process"} or {@code "push"} so the
 * audit feed can show a chronological history of every change.
 */
public record AuditEntry(
    Instant timestamp,
    String raceId,
    String action,
    double gamma,
    double penaltyPool,
    List<Adjustment> adjustments,
    String notes)
{
}
