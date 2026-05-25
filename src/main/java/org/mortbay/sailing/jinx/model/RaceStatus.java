package org.mortbay.sailing.jinx.model;

/**
 * State machine values used by the race officer UI. See the storyboard
 * (wiki/myc-ro-ui-storyboard.md) for the transition rules.
 */
public enum RaceStatus
{
    /** Race exists, no setup done. */
    SCHEDULED,
    /** t_target / start times computed and pushed to SailSys. */
    START_TIMES_SET,
    /** Finish times and statuses captured; not yet processed. */
    RESULTS_ENTERED,
    /** New TCFs computed locally; not yet pushed. */
    PROCESSED,
    /** TCFs written back to SailSys and read-back verified. */
    PUBLISHED
}
