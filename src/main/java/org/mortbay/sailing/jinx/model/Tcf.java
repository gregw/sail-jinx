package org.mortbay.sailing.jinx.model;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Time Correction Factors are held to four decimal places.
 *
 * <p>Four decimals is the sailing convention, and the practical reason is
 * communication: a TCF gets read out, written on a whiteboard, and typed into
 * another system by hand. A number that renders differently each time it is
 * displayed cannot survive that. Four decimals is, if anything, already
 * generous about the fidelity of the underlying process.
 *
 * <p>Rounding is half-up, not truncation. The pre-v2 code truncated, but only
 * because SailSys's handicap endpoint did and a local value that disagreed with
 * the remote one made the mismatch banner impossible to clear. Nothing requires
 * that now, and truncating on every race would walk the whole fleet's handicaps
 * gently downward across a season.
 *
 * <p>Quantising happens in the compact constructors of {@link Boat},
 * {@link Entrant} and {@link Roster.Entry}, so a TCF is rounded once, at the
 * point it is recorded, on every path in and out of the store — including
 * deserialisation of files written before this rule existed. The handicap
 * engine still works in full precision internally; only what gets stored and
 * shown is quantised.
 */
public final class Tcf
{
    /** Decimal places a stored TCF carries. */
    public static final int DECIMALS = 4;

    private Tcf()
    {
    }

    /**
     * The given TCF at {@link #DECIMALS} decimal places, half-up. Non-finite
     * values pass through untouched — a degenerate handicap calculation should
     * surface as the nonsense it is, not as a rounding exception.
     */
    public static double round(double tcf)
    {
        if (!Double.isFinite(tcf))
            return tcf;
        return BigDecimal.valueOf(tcf).setScale(DECIMALS, RoundingMode.HALF_UP).doubleValue();
    }

    /**
     * The given TCF as a fixed four-decimal string, for display, reports, and
     * anything a human will retype. {@code Double.toString} would render
     * {@code 1.0450} as {@code "1.045"}.
     */
    public static String format(double tcf)
    {
        if (!Double.isFinite(tcf))
            return String.valueOf(tcf);
        return BigDecimal.valueOf(tcf).setScale(DECIMALS, RoundingMode.HALF_UP).toPlainString();
    }
}
