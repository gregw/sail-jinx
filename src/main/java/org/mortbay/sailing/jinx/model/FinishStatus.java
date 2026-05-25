package org.mortbay.sailing.jinx.model;

/**
 * Per-boat finish disposition, as used by the algorithm. See
 * {@code wiki/myc-twilight-handicap-v2.md} section 5 for the rules.
 */
public enum FinishStatus
{
    /** Finished — actual elapsed time used. */
    FIN,
    /** Did not finish — effective elapsed = slowest finisher + dnfAllowance. */
    DNF,
    /** Retired — same treatment as DNF. */
    RET,
    /** Disqualified — excluded from adjustments, TCF unchanged. */
    DSQ,
    /** Did not compete — never on the water. TCF unchanged. */
    DNC,
    /** Did not start — on the water, did not start. TCF unchanged. */
    DNS
}
