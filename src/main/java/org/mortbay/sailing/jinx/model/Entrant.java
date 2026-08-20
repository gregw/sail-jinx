package org.mortbay.sailing.jinx.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One boat entered in one race, with the TCF that applies to it <em>for that
 * race</em>.
 *
 * <p>Identity is denormalised on purpose. {@code sailNumber}, {@code name},
 * {@code division} and {@code spinnaker} are copied from the register at entry
 * time rather than looked up on read, so that
 * <ul>
 *   <li>a one-off entrant — which has no register boat at all — renders the
 *       same way as everyone else, and</li>
 *   <li>renaming or retiring a boat next season doesn't quietly rewrite the
 *       history of races it already sailed.</li>
 * </ul>
 *
 * <p>{@code boatId} is null for {@link EntryType#ONE_OFF}. Every other entry
 * type references a register boat.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Entrant(
    String boatId,
    String sailNumber,
    String name,
    String division,
    String designId,
    Spinnaker spinnaker,
    double tcf,
    EntryType entryType)
{
    public Entrant
    {
        // Quantise on every construction path — engine output, hand-typed edit,
        // and deserialisation of a file written before the rule existed. See
        // Tcf for why four decimals.
        tcf = Tcf.round(tcf);
    }

    /**
     * How this boat came to be in the race. Only affects whether the handicap
     * engine adjusts its TCF afterwards — see {@link #scoresHandicap()}.
     */
    public enum EntryType
    {
        /** Entered for the season on the series roster. The normal case. */
        ROSTER,
        /** Turned up on the night and was added to the register there and then. */
        CASUAL,
        /** Sailed once, not in the register, carries no handicap forward. */
        ONE_OFF
    }

    /**
     * Entrant for a registered boat on the terms of its series entry. The TCF, division
     * and spinnaker come from the entry rather than the boat, because they are not
     * properties of the hull — see {@link Boat}.
     */
    public static Entrant fromRosterEntry(Boat boat, Roster.Entry entry)
    {
        return new Entrant(boat.id(), boat.sailNumber(), boat.name(),
            entry.division(), boat.designId(), entry.spinnaker(), entry.startingTcf(),
            EntryType.ROSTER);
    }

    /**
     * Entrant for a registered boat entered directly into a race — a casual arriving on
     * the night, with no series entry to take its terms from.
     */
    public static Entrant fromBoat(Boat boat, double tcf, String division,
                                   Spinnaker spinnaker, EntryType entryType)
    {
        return new Entrant(boat.id(), boat.sailNumber(), boat.name(),
            division, boat.designId(), spinnaker, tcf, entryType);
    }

    /**
     * Entrant for a visitor who is not in the register and is not being added
     * to it. Gets a TCF so it can be given a start time and be placed, but its
     * TCF goes nowhere afterwards.
     */
    public static Entrant oneOff(String name, String sailNumber, double tcf)
    {
        return new Entrant(null, sailNumber, name, null, null, null, tcf, EntryType.ONE_OFF);
    }

    /**
     * True when this entrant's handicap should be adjusted by the race it just sailed.
     * False only for one-offs: they have no register boat for a new TCF to belong to.
     *
     * <p>A casual <em>does</em> score — it sailed the race like everybody else. What it
     * does not do is come back automatically; see {@link #seedsNextRace()}.
     */
    public boolean scoresHandicap()
    {
        return boatId != null && entryType != EntryType.ONE_OFF;
    }

    /**
     * True when this entrant should be carried into the next race automatically.
     *
     * <p>Only boats on the series roster. A casual turned up this week and may not next
     * week, so seeding it would put a boat on the start sheet that nobody expects — and
     * the RO would have to notice and remove it every time. Adding it again takes two
     * clicks; explaining a phantom entry on a printed start sheet does not.
     */
    public boolean seedsNextRace()
    {
        return boatId != null && entryType == EntryType.ROSTER;
    }
}
