package org.mortbay.sailing.jinx.model;

/**
 * Per-boat finish disposition, as used by the algorithm. See
 * {@code wiki/myc-twilight-handicap-v2.md} section 5 for the rules.
 */
public enum FinishStatus
{
    /** Finished — actual elapsed time used. */
    FIN,
    /**
     * Did not finish — still racing when the race ended. Effective elapsed = slowest
     * finisher + dnfAllowance, and the handicap eases: running out of time is a
     * statement about the boat's speed.
     */
    DNF,
    /**
     * Retired — stopped for a reason of its own. <b>Not</b> the same as DNF: the TCF is
     * frozen and the boat takes no part in the handicap.
     *
     * <p>Gear failure, an injury, somewhere else to be — none of that says anything about
     * how fast the boat is, so easing its handicap would reward a bad night with a better
     * start, and a boat that retired often would ratchet its way down the fleet without
     * ever sailing a race.
     */
    RET,
    /** Disqualified — excluded from adjustments, TCF unchanged. */
    DSQ,
    /** Did not compete — never on the water. TCF unchanged. */
    DNC,
    /** Did not start — on the water, did not start. TCF unchanged. */
    DNS
}
