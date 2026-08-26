package org.mortbay.sailing.jinx.model;

/**
 * A boat in the club's fleet register — roughly forty of them, edited by hand on the
 * Boats page.
 *
 * <p>This record holds only what is true of the <b>hull itself</b>: what it is called,
 * what is painted on its sail, and what it was built as. Those facts follow the boat
 * from series to series and from club to club.
 *
 * <p>Deliberately <em>not</em> here: <b>TCF, division and spinnaker</b>. None of them is
 * a property of a boat. A boat does not have a handicap — it has one <em>for a given
 * series</em>, and a different one by the end of it. It can sail one series in Division 1
 * and the next in Division 2, or enter one with a kite and one without. Those live on
 * {@link Entrant}, per race, which is also what makes race 4's handicaps survive race 5
 * being processed.
 *
 * <p>{@code designId} is the hull type, learned from whatever was typed when the boat was
 * entered and resolved through the alias seed. Null when unknown, which is normal — the
 * Jinx handicap scores on TCF alone. A design-less boat is <em>upgraded</em> in place the
 * day its design becomes known, rather than becoming a second record.
 *
 * <p>Retiring a boat sets {@code active} false rather than deleting it, because past
 * races still have to render its name and sail number. {@code casual} marks a boat added
 * on a race night rather than registered before the season — it changes nothing
 * functionally, it just lets the register be tidied later.
 */
public record Boat(
    String id,
    String sailNumber,
    String name,
    String designId,
    boolean casual,
    boolean active,
    String notes)
{
}
