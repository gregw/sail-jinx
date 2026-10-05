package org.mortbay.sailing.jinx.pursuit;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mortbay.sailing.jinx.config.JinxConfig;
import org.mortbay.sailing.jinx.model.Adjustment;
import org.mortbay.sailing.jinx.model.FinishStatus;
import org.mortbay.sailing.jinx.model.Race;
import org.mortbay.sailing.jinx.model.Result;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.closeTo;
import static org.hamcrest.Matchers.is;

/**
 * The giveback in whole minutes.
 *
 * <p>The penalty ladder charges 5, 4, 3, 2, 1; the pool comes back a minute at a time —
 * one to each boat that ran out of time, then one to the last boat home, one to the
 * second last, and so on up the fleet, stopping short of the penalty places. Read as a
 * ladder, a night is {@code +5 +4 +3 +2 +1 0 … 0 −1 … −1}, which the committee can check
 * by eye.
 *
 * <p>Only the edges are fractional, and each is named here: more DNFs than minutes, and
 * the minutes left over for the boats that stayed home.
 */
class MinuteGivebackTest
{
    private static final List<Double> PENALTIES = List.of(5.0, 4.0, 3.0, 2.0, 1.0);

    private static JinxConfig.Algorithm alg()
    {
        return new JinxConfig.Algorithm(PENALTIES, 90, "18:00", -33.8, 151.2833, false);
    }

    private static Race race()
    {
        return new Race("r1", "s1", 1, "R1", LocalDate.of(2026, 5, 1),
            LocalTime.of(18, 0), 90, false);
    }

    /**
     * {@code entered} boats on TCF 1.0: the first {@code finishers} finish one minute
     * apart in id order, the next {@code dnfs} run out of time, the rest never came.
     */
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
                int secs = (60 + i) * 60;
                results.put(id, new Result(id, FinishStatus.FIN, LocalTime.MIDNIGHT,
                    LocalTime.MIDNIGHT.plusSeconds(secs), null, i + 1, secs));
            }
            else if (i < finishers + dnfs)
                results.put(id, new Result(id, FinishStatus.DNF, null, null, null));
            else
                results.put(id, new Result(id, FinishStatus.DNC, null, null, null));
        }
        return new Fixture(boats, results);
    }

    /**
     * As {@link #night}, with the boats after the finishers and DNFs split: {@code avgs}
     * duty boats, then {@code dnss} that came and did not start, then {@code dncs} that
     * stayed home.
     */
    private static Fixture night(int finishers, int dnfs, int avgs, int dnss, int dncs)
    {
        Fixture f = night(finishers + dnfs + avgs + dnss + dncs, finishers, dnfs);
        for (int i = finishers + dnfs; i < finishers + dnfs + avgs + dnss; i++)
        {
            String id = "b" + i;
            FinishStatus status = i < finishers + dnfs + avgs ? FinishStatus.AVG : FinishStatus.DNS;
            f.results().put(id, new Result(id, status, null, null, null));
        }
        return f;
    }

    private record Fixture(List<Competitor> boats, Map<String, Result> results) {}

    private static Map<String, Adjustment> run(Fixture f)
    {
        return run(f, null);
    }

    private static Map<String, Adjustment> run(Fixture f, Integer nextRaceMinutes)
    {
        Map<String, Adjustment> out = new LinkedHashMap<>();
        for (Adjustment a : new PursuitHandicapEngine(alg()).processResults(
            f.boats(), race(), f.results(), nextRaceMinutes))
            out.put(a.boatId(), a);
        return out;
    }

    private static double net(Map<String, Adjustment> adj, int i)
    {
        return adj.get("b" + i).netAdjustmentMinutes();
    }

    private static double sumOfNets(Map<String, Adjustment> adj)
    {
        return adj.values().stream().mapToDouble(Adjustment::netAdjustmentMinutes).sum();
    }

    // ---------------------------------------------------------------------------

    /** Twenty out, twenty home: fifteen charged, one each to the fifteen behind. */
    @Test
    void theMinutesExactlyFitTheBoatsBehindThePenaltyPlaces()
    {
        Map<String, Adjustment> adj = run(night(20, 20, 0));
        for (int i = 0; i < 5; i++)
            assertThat("b" + i, net(adj, i), is(closeTo(PENALTIES.get(i), 1e-9)));
        for (int i = 5; i < 20; i++)
            assertThat("b" + i, net(adj, i), is(closeTo(-1.0, 1e-9)));
    }

    /**
     * Thirty home: the minutes run out before they reach the middle of the fleet. The
     * boats between the last penalty place and the last fifteen get nothing.
     */
    @Test
    void theMiddleOfABigFleetIsLeftAlone()
    {
        Map<String, Adjustment> adj = run(night(30, 30, 0));
        for (int i = 5; i < 15; i++)
            assertThat("b" + i, net(adj, i), is(closeTo(0.0, 1e-9)));
        for (int i = 15; i < 30; i++)
            assertThat("b" + i, net(adj, i), is(closeTo(-1.0, 1e-9)));
    }

    /** A boat that ran out of time is served before the last boat home. */
    @Test
    void theBoatsThatRanOutOfTimeAreServedFirst()
    {
        // 28 home, 2 DNF: the DNFs take two minutes, so only the last thirteen home get one.
        Map<String, Adjustment> adj = run(night(30, 28, 2));
        assertThat(net(adj, 28), is(closeTo(-1.0, 1e-9)));
        assertThat(net(adj, 29), is(closeTo(-1.0, 1e-9)));
        assertThat(net(adj, 14), is(closeTo(0.0, 1e-9)));
        for (int i = 15; i < 28; i++)
            assertThat("b" + i, net(adj, i), is(closeTo(-1.0, 1e-9)));
    }

    /** More DNFs than minutes: there is no fair way to pick, so they share evenly. */
    @Test
    void moreRetirementsThanMinutesShareThePoolEvenly()
    {
        Map<String, Adjustment> adj = run(night(30, 5, 20));
        for (int i = 5; i < 25; i++)
            assertThat("b" + i, net(adj, i), is(closeTo(-0.75, 1e-9)));
        for (int i = 25; i < 30; i++)
            assertThat("b" + i + " stayed home", net(adj, i), is(closeTo(0.0, 1e-9)));
    }

    /**
     * A thin night: eight home out of thirty. Three boats get a minute each, and the
     * twelve minutes nobody out there could take go evenly to the twenty-two at home.
     */
    @Test
    void whatTheRacersCannotTakeGoesEvenlyToTheBoatsAtHome()
    {
        Map<String, Adjustment> adj = run(night(30, 8, 0));
        for (int i = 5; i < 8; i++)
            assertThat("b" + i, net(adj, i), is(closeTo(-1.0, 1e-9)));
        for (int i = 8; i < 30; i++)
            assertThat("b" + i, net(adj, i), is(closeTo(-12.0 / 22.0, 1e-9)));
    }

    /**
     * Nobody ever gets more than a minute back. With nobody at home to take the rest, the
     * minutes the racers cannot take are discarded — which only happens when the whole
     * entry list turned up and too few got round behind the ladder.
     */
    @Test
    void withNobodyAtHomeTheLeftoverIsDiscarded()
    {
        // Seven, all home: fifteen charged, two receivers, a minute each, thirteen gone.
        Map<String, Adjustment> adj = run(night(7, 7, 0));
        assertThat(net(adj, 6), is(closeTo(-1.0, 1e-9)));
        assertThat(net(adj, 5), is(closeTo(-1.0, 1e-9)));
        assertThat(net(adj, 0), is(closeTo(5.0, 1e-9)));
        assertThat(sumOfNets(adj), is(closeTo(13.0, 1e-9)));

        // Six home and two DNF: still a minute each, DNFs included.
        adj = run(night(8, 6, 2));
        assertThat(net(adj, 6), is(closeTo(-1.0, 1e-9)));
        assertThat(net(adj, 7), is(closeTo(-1.0, 1e-9)));
        assertThat(net(adj, 5), is(closeTo(-1.0, 1e-9)));
    }

    /** Five boats, all on the ladder, nobody else: nothing is charged. */
    @Test
    void nobodyToReceiveMeansNothingIsCharged()
    {
        Map<String, Adjustment> adj = run(night(5, 5, 0));
        for (Adjustment a : adj.values())
        {
            assertThat(a.boatId(), a.netAdjustmentMinutes(), is(closeTo(0.0, 1e-9)));
            assertThat(a.boatId(), a.newTcf(), is(closeTo(a.oldTcf(), 1e-12)));
        }
    }

    /**
     * A thin night with a duty boat and boats that came and did not start. They were
     * there, so they are served before the boats that stayed home: the duty boats first,
     * then the DNSs, a minute each at most, and only then the DNCs share what is left.
     */
    @Test
    void dutyBoatsThenNonStartersAreServedBeforeTheBoatsAtHome()
    {
        // 8 home, 2 AVG, 3 DNS, 17 DNC: fifteen charged; three to the finishers behind the
        // ladder, two to the duty boats, three to the DNSs, and seven for the seventeen.
        Map<String, Adjustment> adj = run(night(8, 0, 2, 3, 17));
        for (int i = 5; i < 8; i++)
            assertThat("b" + i, net(adj, i), is(closeTo(-1.0, 1e-9)));
        for (int i = 8; i < 10; i++)
            assertThat("b" + i + " AVG", net(adj, i), is(closeTo(-1.0, 1e-9)));
        for (int i = 10; i < 13; i++)
            assertThat("b" + i + " DNS", net(adj, i), is(closeTo(-1.0, 1e-9)));
        for (int i = 13; i < 30; i++)
            assertThat("b" + i + " DNC", net(adj, i), is(closeTo(-7.0 / 17.0, 1e-9)));
        assertThat(sumOfNets(adj), is(closeTo(0.0, 1e-9)));
    }

    /** Fewer minutes than duty boats: they share evenly, and nobody behind them gets any. */
    @Test
    void dutyBoatsShareWhatIsLeftWhenThereIsLessThanAMinuteEach()
    {
        // 18 home: thirteen behind the ladder take thirteen, two minutes for three AVGs.
        Map<String, Adjustment> adj = run(night(18, 0, 3, 2, 2));
        for (int i = 18; i < 21; i++)
            assertThat("b" + i + " AVG", net(adj, i), is(closeTo(-2.0 / 3.0, 1e-9)));
        for (int i = 21; i < 25; i++)
            assertThat("b" + i, net(adj, i), is(closeTo(0.0, 1e-9)));
    }

    /** What the duty boat leaves, the DNSs share evenly, still at most a minute each. */
    @Test
    void nonStartersShareWhatTheDutyBoatLeaves()
    {
        // 18 home: two minutes left; one to the AVG, one for three DNSs, none for the DNCs.
        Map<String, Adjustment> adj = run(night(18, 0, 1, 3, 2));
        assertThat("b18 AVG", net(adj, 18), is(closeTo(-1.0, 1e-9)));
        for (int i = 19; i < 22; i++)
            assertThat("b" + i + " DNS", net(adj, i), is(closeTo(-1.0 / 3.0, 1e-9)));
        for (int i = 22; i < 24; i++)
            assertThat("b" + i + " DNC", net(adj, i), is(closeTo(0.0, 1e-9)));
    }

    /** The racers come first: a full night leaves nothing for the duty boat or the DNSs. */
    @Test
    void dutyBoatsAndNonStartersWaitForTheRacers()
    {
        Map<String, Adjustment> adj = run(night(30, 0, 1, 1, 1));
        for (int i = 30; i < 33; i++)
            assertThat("b" + i, net(adj, i), is(closeTo(0.0, 1e-9)));

        // More DNFs than minutes: they share it all, as before.
        adj = run(night(5, 20, 1, 1, 0));
        assertThat("b25 AVG", net(adj, 25), is(closeTo(0.0, 1e-9)));
        assertThat("b26 DNS", net(adj, 26), is(closeTo(0.0, 1e-9)));
    }

    /** No DNCs: the duty boat and the DNSs take a minute each and the rest is discarded. */
    @Test
    void withNobodyAtHomeDutyBoatsAndNonStartersStillStopAtAMinute()
    {
        // 7 home, 1 AVG, 1 DNS: two to the racers, one each to the AVG and DNS, 11 gone.
        Map<String, Adjustment> adj = run(night(7, 0, 1, 1, 0));
        assertThat("b7 AVG", net(adj, 7), is(closeTo(-1.0, 1e-9)));
        assertThat("b8 DNS", net(adj, 8), is(closeTo(-1.0, 1e-9)));
        assertThat(sumOfNets(adj), is(closeTo(11.0, 1e-9)));
    }

    /** Whenever somebody is at home to take what is left, nothing is discarded. */
    @Test
    void everyMinuteChargedComesBack()
    {
        assertThat(sumOfNets(run(night(20, 20, 0))), is(closeTo(0.0, 1e-9)));
        assertThat(sumOfNets(run(night(30, 30, 0))), is(closeTo(0.0, 1e-9)));
        assertThat(sumOfNets(run(night(30, 28, 2))), is(closeTo(0.0, 1e-9)));
        assertThat(sumOfNets(run(night(30, 5, 20))), is(closeTo(0.0, 1e-9)));
        assertThat(sumOfNets(run(night(30, 8, 0))), is(closeTo(0.0, 1e-9)));
    }

    /**
     * A five-minute penalty is five minutes of the NEXT race: the TCF change is measured
     * against the race it will be sailed in, not the one that earned it.
     */
    @Test
    void theTcfChangeIsMeasuredAgainstTheNextRace()
    {
        Map<String, Adjustment> adj = run(night(20, 20, 0), 60);
        // τ = T·med/tcf; with everyone on 1.0, 1/(1 − 5/60) moves τ by exactly 5 of 60.
        assertThat(adj.get("b0").newTcf(), is(closeTo(1.0 / (1.0 - 5.0 / 60.0), 1e-12)));
        assertThat(60.0 - 60.0 / adj.get("b0").newTcf(), is(closeTo(5.0, 1e-9)));
    }

    /** The last race of a series has no next one; it falls back to its own target. */
    @Test
    void withNoNextRaceItsOwnTargetIsUsed()
    {
        Map<String, Adjustment> adj = run(night(20, 20, 0), null);
        assertThat(adj.get("b0").newTcf(), is(closeTo(1.0 / (1.0 - 5.0 / 90.0), 1e-12)));
    }
}
