package org.mortbay.sailing.jinx.model;

import java.time.Instant;
import java.util.List;

/**
 * Audit record of a processed race. Written when handicaps are saved or a race is
 * unlocked, and never modified.
 *
 * <p>{@code action} is a short tag like {@code "save-handicaps"} or {@code "unlock"} so
 * the audit feed can show a chronological history of every change.
 *
 * <p>{@code user} is the club address of whoever did it. <b>Null is a real answer</b>,
 * and means the server had no login configured when the entry was written — the
 * single-machine deployment, where every request is an admin and there is nobody to
 * name. Recording a placeholder there would be inventing an identity: "local" is a lie
 * on a networked server running with authentication off, which is a configuration that
 * exists. Entries written before this field did the same thing for a different reason,
 * and read back the same way.
 */
public record AuditEntry(
    Instant timestamp,
    String raceId,
    String action,
    String user,
    double gamma,
    double penaltyPool,
    List<Adjustment> adjustments,
    String notes)
{
    /** An entry nobody can be named for: authentication is off. */
    public AuditEntry(Instant timestamp, String raceId, String action, double gamma,
        double penaltyPool, List<Adjustment> adjustments, String notes)
    {
        this(timestamp, raceId, action, null, gamma, penaltyPool, adjustments, notes);
    }
}
