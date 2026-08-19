package org.mortbay.sailing.jinx.model;

/**
 * A series of races — one season of one club event, e.g. "2026 Winter Twilight".
 * Created by hand in the app; there is no external system to import one from.
 *
 * <p>Past seasons stay readable forever: they are the only record of what a boat's TCF
 * was at the time. {@code archived} hides a finished one from the default lists without
 * deleting it.
 *
 * <p>{@code raceFormat} and {@code handicapAlgorithm} each currently have exactly one
 * supported value. They are recorded anyway so that a series created today says what it
 * is, rather than being retro-labelled when a second option appears — at which point
 * existing series must not silently change meaning.
 */
public record Series(
    String id,
    String name,
    SpinnakerPolicy spinnakerPolicy,
    RaceFormat raceFormat,
    HandicapAlgorithm handicapAlgorithm,
    boolean archived)
{
    public Series
    {
        if (spinnakerPolicy == null)
            spinnakerPolicy = SpinnakerPolicy.MIXED;
        if (raceFormat == null)
            raceFormat = RaceFormat.PURSUIT;
        if (handicapAlgorithm == null)
            handicapAlgorithm = HandicapAlgorithm.JINX;
    }

    /** Series with the defaults — pursuit, Jinx, mixed spinnaker. */
    public Series(String id, String name, boolean archived)
    {
        this(id, name, null, null, null, archived);
    }

    /** What the fleet may fly, which decides the default for a roster entry. */
    public enum SpinnakerPolicy
    {
        /** Everyone flies one. */
        SPINNAKER,
        /** Nobody does. */
        NON_SPINNAKER,
        /** Per boat — the roster entry decides. */
        MIXED;

        /** The spinnaker a boat entering this series defaults to, or null when it is a per-boat choice. */
        public Spinnaker defaultSpinnaker()
        {
            return switch (this)
            {
                case SPINNAKER -> Spinnaker.S;
                case NON_SPINNAKER -> Spinnaker.NS;
                case MIXED -> null;
            };
        }
    }

    /** How the fleet is started and scored. */
    public enum RaceFormat
    {
        /** Every boat gets its own staggered gun; first over the line wins. The only one built. */
        PURSUIT,
        /** Staggered start, then corrected on TCF. Not implemented. */
        TCF_PURSUIT,
        /** One gun, corrected on PHS. Not implemented. */
        PHS;

        public boolean isSupported()
        {
            return this == PURSUIT;
        }
    }

    /** What adjusts the handicaps between races. */
    public enum HandicapAlgorithm
    {
        /** The punitive pursuit handicap this application exists for. The only one built. */
        JINX,
        /** No adjustment: handicaps stay where they are put. Not implemented. */
        SCRATCH;

        public boolean isSupported()
        {
            return this == JINX;
        }
    }
}
