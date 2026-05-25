package org.mortbay.sailing.jinx.model;

/**
 * A boat in the configured series. The boatId is the SailSys integer id (as a string)
 * so it is stable across runs and round-trips cleanly to the SailSys API.
 *
 * <p>{@code currentTcf} carries the locally-tracked Time Correction Factor — the
 * authoritative value for sail-jinx. SailSys is the system of record for boat
 * identity (name, sail number, division), not for the TCF managed here.
 */
public record Boat(
    String id,            // SailSys boatId, e.g. "12345"
    String name,
    String sailNumber,
    String divisionId,
    String divisionName,
    double currentTcf)
{
}
