package org.mortbay.sailing.jinx.pursuit;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mortbay.sailing.jinx.config.JinxConfig;
import org.mortbay.sailing.jinx.config.JinxConfig.PenaltyScaling;
import org.mortbay.sailing.jinx.model.Adjustment;
import org.mortbay.sailing.jinx.model.FinishStatus;
import org.mortbay.sailing.jinx.model.Race;
import org.mortbay.sailing.jinx.model.Result;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.closeTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.lessThan;

/**
 * The giveback, as weights over the whole entry list.
 *
 * <p>This is the executable half of wiki §6.3. The rule it exists to pin is the one the
 * club arrived at from a spreadsheet: <b>the penalty pool comes back to every boat
 * entered in the race, not to the boats that raced</b>, and a boat that stayed home
 * counts as a fraction of a boat — the fraction of the fleet that stayed home.
 *
 * <p>The failure it was written against: thirty boats in the series, five turn up on a
 * filthy night and all finish. Under the old rule they were the only recipients, so the
 * fifteen minutes they had just been charged came straight back to the five of them, and
 * the boat that finished last of five collected six minutes for it. See
 * {@link #aBoatThatIsPenalisedNeverGetsAnythingBack} and
 * {@link #theGivebackPerFinisherHardlyMovesWithTurnout}.
 */
class GivebackWeightsTest
{
    private static final List<Double> PENALTIES = List.of(5.0, 4.0, 3.0, 2.0, 1.0);

    private static JinxConfig.Algorithm alg()
    {
        return alg(1.2, 0.2, 0.0);
    }

    private static JinxConfig.Algorithm alg(double dnfWeight, double dncWeight, double gamma)
    {
        return new JinxConfig.Algorithm(PENALTIES, 90, "18:00", -33.8, 151.2833,
            false, null, PenaltyScaling.FIXED, gamma, dnfWeight, dncWeight);
    }

    private static Race race()
    {
        return new Race("r1", "s1", 1, "R1", LocalDate.of(2026, 5, 1),
            LocalTime.of(18, 0), 90, false);
    }

    /**
     * A fleet of {@code entered} boats of which {@code finishers} finish, one minute
     * apart, and the rest never came. Every boat is on TCF 1.0, so the arithmetic is
     * readable: the TCF conversion is a straight division by 90.
     */
    private static Fixture night(int entered, int finishers)
    {
        return night(entered, finishers, 0);
    }

    private static Fixture night(int entered, int finishers, int dnfs)
    {
        List<Competitor> boats = new ArrayList<>();
        Map<String, Result> results = new LinkedHashMap<>();
        for (int i = 0; i < entered; i++)
        {
            String id = "b" + i;
            boats.add(new Competitor(id, 1.0, true));
            if (i < finishers)
            {
                // One minute apart off a common gun, so gap == elapsed difference.
                int secs = (60 + i) * 60;
                results.put(id, new Result(id, FinishStatus.FIN, LocalTime.MIDNIGHT,
                    LocalTime.MIDNIGHT.plusSeconds(secs), null, i + 1, secs));
            }
            else if (i < finishers + dnfs)
            {
                results.put(id, new Result(id, FinishStatus.DNF, null, null, null));
            }
            else
            {
                results.put(id, new Result(id, FinishStatus.DNC, null, null, null));
            }
        }
        return new Fixture(boats, results);
    }

    private record Fixture(List<Competitor> boats, Map<String, Result> results) {}

    private static Map<String, Adjustment> run(JinxConfig.Algorithm cfg, Fixture f)
    {
        Map<String, Adjustment> out = new LinkedHashMap<>();
        for (Adjustment a : new PursuitHandicapEngine(cfg).processResults(
            f.boats(), race(), f.results()))
            out.put(a.boatId(), a);
        return out;
    }

    private static double sumOfNets(Map<String, Adjustment> adj)
    {
        return adj.values().stream().mapToDouble(Adjustment::netAdjustmentMinutes).sum();
    }

    // ---------------------------------------------------------------------------

    /**
     * The invariant the whole scheme rests on: exactly the time charged is the time
     * given back, counted over <b>every boat entered</b> — not over the boats that
     * raced. Without it the fleet's start times drift away from each other race by race.
     */
    @Test
    void everyMinuteChargedComesBackToTheEntryList()
    {
        assertThat(sumOfNets(run(alg(), night(30, 25, 1))), is(closeTo(0.0, 1e-9)));
        assertThat(sumOfNets(run(alg(), night(30, 5))), is(closeTo(0.0, 1e-9)));
        assertThat(sumOfNets(run(alg(), night(30, 30))), is(closeTo(0.0, 1e-9)));
    }

    /**
     * The failure that started this. A boat in a penalty place carries weight zero, so
     * it pays its penalty and receives nothing — whatever the turnout.
     *
     * <p>Five boats out of thirty, all finishing: the boat that came fifth used to be
     * charged one minute and handed six back, which made finishing last of five the best
     * handicap outcome available that night.
     */
    @Test
    void aBoatThatIsPenalisedNeverGetsAnythingBack()
    {
        Map<String, Adjustment> adj = run(alg(), night(30, 5));
        for (int i = 0; i < 5; i++)
        {
            Adjustment a = adj.get("b" + i);
            assertThat("b" + i + " reward", a.rewardMinutes(), is(closeTo(0.0, 1e-9)));
            assertThat("b" + i + " net", a.netAdjustmentMinutes(),
                is(closeTo(PENALTIES.get(i), 1e-9)));
        }
        // …and the fifteen minutes went to the twenty-five that stayed home.
        assertThat(adj.get("b29").rewardMinutes(), is(closeTo(0.6, 1e-9)));
    }

    /**
     * A boat that stayed home counts as {@code dncWeight × (stayed home / entered)} of
     * an ordinary finisher. Twenty-five of thirty away, at the default 0.2, is
     * {@code 0.2 × 25/30 = 0.1667} each.
     *
     * <p>The shape is the point: on a full night the handful of absentees are worth
     * almost nothing, and on an empty one they carry most of the fleet's weight. Absence
     * can never be worth more than racing, because the fraction cannot exceed one.
     */
    @Test
    void aBoatThatStayedHomeCountsAsAFractionOfTheFleetThatStayedHome()
    {
        // 20 of 30 racing: 15 non-penalised finishers at weight 1, 10 away at 0.2×10/30.
        Map<String, Adjustment> adj = run(alg(), night(30, 20));
        double perFinisher = adj.get("b19").rewardMinutes();
        double perAbsentee = adj.get("b29").rewardMinutes();
        assertThat(perAbsentee / perFinisher, is(closeTo(0.2 * 10.0 / 30.0, 1e-9)));
        assertThat("absence is never worth more than racing", perAbsentee,
            is(lessThan(perFinisher)));
    }

    /**
     * The property the change exists to buy: what an ordinary finisher gains should
     * hardly depend on how many boats turned up. It used to scale as 1/turnout — a boat
     * finishing mid-fleet on a five-boat night gained five times what the same finish
     * was worth on a full one.
     *
     * <p>The absentees are what holds it steady: as the racing fleet shrinks there are
     * fewer boats to share the pool, but more of it leaves the racing fleet altogether.
     */
    @Test
    void theGivebackPerFinisherHardlyMovesWithTurnout()
    {
        // dncWeight 1.0 is the setting that holds it flattest; 0.2 is the club's, and
        // is tuning. Both are far better than the 1/turnout the old rule gave.
        JinxConfig.Algorithm cfg = alg(1.2, 1.0, 0.0);
        double full = run(cfg, night(30, 30)).get("b29").rewardMinutes();
        for (int came : new int[] {25, 20, 15, 10, 6})
        {
            double thin = run(cfg, night(30, came)).get("b" + (came - 1)).rewardMinutes();
            assertThat("came " + came, thin / full, is(closeTo(1.0, 0.5)));
        }
    }

    /** A boat that ran out of time draws more than one that finished: {@code dnfWeight}. */
    @Test
    void aBoatThatRanOutOfTimeDrawsMoreThanAFinisher()
    {
        Map<String, Adjustment> adj = run(alg(), night(30, 20, 5));
        double finisher = adj.get("b19").rewardMinutes();
        double dnf = adj.get("b20").rewardMinutes();
        assertThat(dnf / finisher, is(closeTo(1.2, 1e-9)));
    }

    /**
     * Retirements stay frozen, and this is the test that says so under the new weights.
     * A boat that stopped for a reason of its own has said nothing about its speed, so
     * it neither pays nor draws — unlike DNF, which it looks like on the results sheet.
     */
    @Test
    void aRetirementIsStillFrozenUnderTheNewWeights()
    {
        Fixture f = night(30, 20);
        f.results().put("b25", new Result("b25", FinishStatus.RET, null, null, null));
        Map<String, Adjustment> adj = run(alg(), f);
        assertThat(adj.get("b25").rewardMinutes(), is(closeTo(0.0, 1e-9)));
        assertThat(adj.get("b25").newTcf(), is(closeTo(1.0, 1e-9)));
    }

    /**
     * Nobody to give it back to, so nothing is charged.
     *
     * <p>The case is a fleet no bigger than the penalty list — one boat sailing alone,
     * or a five-boat series scored on {@code [5,4,3,2,1]}. Every boat that raced is on
     * the ladder, none of them may receive, and there is nobody at home either. Keeping
     * the pool would move the whole fleet's handicaps against a fleet that isn't there;
     * wiki §9 has always promised that a boat racing alone finishes where it started.
     */
    @Test
    void whenThereIsNobodyToGiveItBackToNothingIsCharged()
    {
        for (int fleet : new int[] {1, 3, 5})
        {
            Map<String, Adjustment> adj = run(alg(), night(fleet, fleet));
            for (Adjustment a : adj.values())
            {
                assertThat("fleet " + fleet + " " + a.boatId() + " penalty",
                    a.penaltyMinutes(), is(closeTo(0.0, 1e-9)));
                assertThat("fleet " + fleet + " " + a.boatId() + " tcf",
                    a.newTcf(), is(closeTo(a.oldTcf(), 1e-9)));
            }
        }
    }

    /**
     * Two rows straight off the committee's spreadsheet, to four decimal places.
     *
     * <p>The model is the specification here — the weights, the two knobs and the
     * decision to share over the entry list were all settled in it before any of this was
     * written, so it is the thing this implementation has to agree with. If a change
     * makes these numbers move, the model is what has to move first.
     *
     * <pre>
     *   fleet 30, penalties [5,4,3,2,1], dnfWeight 1.2, dncWeight 0.2
     *
     *   DNF  DNC  came  fin  pen  non-pen  pool  Σw        finisher   DNF       DNC
     *    0    8    22    22    5    17      15   17.4267   -0.8607   -1.0329   -0.0459
     *    5   15    15    10    5     5      15   12.5000   -1.2000   -1.4400   -0.1200
     * </pre>
     */
    @Test
    void theEngineAgreesWithTheModelTheCommitteeSignedOff()
    {
        Map<String, Adjustment> eightAway = run(alg(), night(30, 22));
        assertThat(eightAway.get("b21").rewardMinutes(), is(closeTo(0.8607, 1e-4)));
        assertThat(eightAway.get("b29").rewardMinutes(), is(closeTo(0.0459, 1e-4)));

        Map<String, Adjustment> hardNight = run(alg(), night(30, 10, 5));
        assertThat(hardNight.get("b9").rewardMinutes(), is(closeTo(1.2000, 1e-4)));
        assertThat(hardNight.get("b10").rewardMinutes(), is(closeTo(1.4400, 1e-4)));
        assertThat(hardNight.get("b29").rewardMinutes(), is(closeTo(0.1200, 1e-4)));
    }

    /**
     * The proportional weighting moves the pool away from the boats that stayed home and
     * towards the boats that raced.
     *
     * <p>γ gives a finisher {@code 1 + γ·gap/maxGap} — one boat for getting round and up
     * to one more for how far behind the first boat home it crossed — and a DNF the same
     * bonus through {@code dnfWeight × the last finisher's weight}. {@code dncWeight} is
     * the only weight that does not move with it, so as the dial turns the boats at home
     * hold a smaller share of a fixed pool and the boats that raced hold a larger one.
     *
     * <p><b>Within the boats that raced, it does not favour the finishers.</b> The DNF
     * takes the same γ bonus they do, so the finishers' total is flat across the dial —
     * what changes is the spread among them, and how much leaves the racing fleet. That
     * is the price of {@code dnfWeight} scaling with the last boat home, which is what
     * keeps a retirement the largest single share at every γ (§6.3.1). Leaving
     * {@code dnfWeight} flat gives the finishers the bonus instead and lets the back of
     * the fleet overtake the DNF at about γ = 0.2. The two are identical at γ = 0.
     *
     * <p><b>What a mean-normalised γ got wrong</b>, and why it survived: dividing the
     * finishers through by their own mean kept an ordinary finisher worth exactly one boat
     * at every γ, so the dial only reshuffled the pool among the finishers and never
     * shifted any of it their way. Every γ test compared two finishers with each other,
     * and a ratio cannot see a factor common to both.
     */
    @Test
    void theProportionalWeightingMovesThePoolTowardsTheBoatsThatRaced()
    {
        // Twenty of thirty come, nineteen finish and one runs out of time.
        Map<String, Adjustment> even = run(alg(1.2, 0.2, 0.0), night(30, 19, 1));
        Map<String, Adjustment> byGap = run(alg(1.2, 0.2, 1.0), night(30, 19, 1));

        // The boat furthest behind is worth two boats; one on the leader's own gap would
        // be worth one. Eligible gaps run 5..18 minutes, so 18 is the widest.
        assertThat(byGap.get("b18").rewardMinutes() / byGap.get("b5").rewardMinutes(),
            is(closeTo((1.0 + 1.0) / (1.0 + 5.0 / 18.0), 1e-9)));

        // The boats at home hold less of it…
        assertThat(byGap.get("b29").rewardMinutes(),
            is(lessThan(even.get("b29").rewardMinutes())));
        // …and the boats that raced hold more.
        assertThat(racedTotal(byGap), is(greaterThan(racedTotal(even))));
        // The boat furthest behind gains; the finishers as a group do not, because the
        // DNF takes the same bonus they do.
        assertThat(byGap.get("b18").rewardMinutes(),
            is(greaterThan(even.get("b18").rewardMinutes())));
        assertThat(finisherTotal(byGap), is(greaterThan(finisherTotal(even))));

        // And the pool still balances over the whole entry list, at both settings.
        assertThat(sumOfNets(even), is(closeTo(0.0, 1e-9)));
        assertThat(sumOfNets(byGap), is(closeTo(0.0, 1e-9)));
    }

    /** What every boat that came out drew between them — the finishers and the DNF. */
    private static double racedTotal(Map<String, Adjustment> adj)
    {
        return finisherTotal(adj) + adj.get("b19").rewardMinutes();
    }

    /** What the boats that got round drew between them. */
    private static double finisherTotal(Map<String, Adjustment> adj)
    {
        double total = 0.0;
        for (int i = 0; i <= 18; i++)
            total += adj.get("b" + i).rewardMinutes();
        return total;
    }

    /**
     * γ is the optional proportional weighting: at 0 every eligible finisher draws the
     * same, at 1 a boat's share follows how far behind the first boat home it crossed,
     * as {@code 1 + gap/maxGap}.
     *
     * <p>It is normalised to a mean of one so the other weights stay on the same scale —
     * {@code dnfWeight} and {@code dncWeight} are multiples of "one average finisher",
     * and they would quietly change meaning if the finishers' weights did not average
     * one at every γ.
     */
    @Test
    void gammaSharesTheRemainingPoolByFinishGap()
    {
        Map<String, Adjustment> even = run(alg(1.2, 0.2, 0.0), night(30, 20));
        assertThat(even.get("b5").rewardMinutes(),
            is(closeTo(even.get("b19").rewardMinutes(), 1e-9)));

        Map<String, Adjustment> byGap = run(alg(1.2, 0.2, 1.0), night(30, 20));
        // 6th home is the first boat that may receive; 20th is the last. Their gaps
        // behind the leader are 5 and 19 minutes, so 1 + 5/19 against 1 + 19/19.
        double first = byGap.get("b5").rewardMinutes();
        double last = byGap.get("b19").rewardMinutes();
        assertThat(last / first, is(closeTo((1.0 + 19.0 / 19.0) / (1.0 + 5.0 / 19.0), 1e-9)));
        // …and the pool still balances over the whole entry list.
        assertThat(sumOfNets(byGap), is(closeTo(0.0, 1e-9)));
    }
}
