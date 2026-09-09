package org.mortbay.sailing.jinx.pursuit;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.mortbay.sailing.jinx.config.JinxConfig;
import org.mortbay.sailing.jinx.config.JinxConfig.PenaltyScaling;
import org.mortbay.sailing.jinx.config.JinxConfig.Variant;
import org.mortbay.sailing.jinx.model.Adjustment;
import org.mortbay.sailing.jinx.model.FinishStatus;
import org.mortbay.sailing.jinx.model.Race;
import org.mortbay.sailing.jinx.model.Result;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.closeTo;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

/**
 * The 2×2 of handicap variants, exercised through the one engine that implements them.
 *
 * <p>The interesting one is {@link #perHourWithEvenGivebackIsDurationIndependent}: it is
 * the reason C is the default, and the reason the penalty scaling and the §7 denominator
 * have to be the same measured quantity.
 */
class HandicapVariantTest
{
    /**
     * A two-rung ladder, because every fixture in this file is a five-boat fleet.
     *
     * <p>It used to be {@code [5,4,3,2,1]}, which put every boat in the fixture on the
     * ladder — and since a penalised boat now draws nothing back, that leaves nobody to
     * give the pool to and the engine charges nothing at all. Several tests here went on
     * passing while asserting things about zero. The club's sizing rule is roughly one
     * rung per six boats; two rungs on five boats is generous and leaves three boats
     * eligible, which is what these tests need to say anything.
     */
    private static final List<Double> PENALTIES = List.of(5.0, 4.0);

    private static JinxConfig.Algorithm alg(PenaltyScaling scaling, double gamma)
    {
        return alg(scaling, gamma, 1.2, 0.2);
    }

    /** …and with the two giveback weights, each counted in ordinary finishers. */
    private static JinxConfig.Algorithm alg(PenaltyScaling scaling, double gamma,
        double dnfWeight, double dncWeight)
    {
        return new JinxConfig.Algorithm(PENALTIES, 90, "18:00", -33.8, 151.2833,
            false, null, scaling, gamma, dnfWeight, dncWeight);
    }

    private static JinxConfig.Algorithm alg(Variant v)
    {
        return alg(v.penaltyScaling(), v.givebackGamma());
    }

    private static Race race()
    {
        return new Race("r1", "s1", 1, "R1", LocalDate.of(2026, 5, 1),
            LocalTime.of(18, 0), 90, false);
    }

    /** A boat, its TCF, and the minutes it took. */
    private record Sailed(String id, double tcf, double minutes) {}

    /**
     * A scratch-race fixture: every boat off the same gun, so finish order IS elapsed
     * order. Fine for the penalty ladder and for conservation, and it is what most of
     * this file needs — but see {@link #pursuit} for the giveback, which cannot be
     * tested here at all.
     */
    private static Map<String, Result> resultsOf(List<Sailed> fleet, double scale)
    {
        List<Sailed> byElapsed = fleet.stream()
            .sorted((a, b) -> Double.compare(a.minutes(), b.minutes())).toList();
        Map<String, Result> out = new LinkedHashMap<>();
        for (int i = 0; i < byElapsed.size(); i++)
        {
            Sailed s = byElapsed.get(i);
            long secs = Math.round(s.minutes() * scale * 60.0);
            // Same gun for everyone, so the corrected finish and the elapsed agree.
            out.put(s.id(), new Result(s.id(), FinishStatus.FIN, LocalTime.MIDNIGHT,
                LocalTime.MIDNIGHT.plusSeconds(secs), null, i + 1, (int)secs));
        }
        return out;
    }

    /** A boat in a real pursuit race: when its gun went, and when it crossed. */
    private record Sailing(String id, double tcf, String gun, String finish) {}

    /**
     * A genuine pursuit fixture, where finish order is NOT elapsed order.
     *
     * <p>This distinction is the whole point of the giveback change and it cannot be
     * expressed with {@link #resultsOf}: there every boat shares a gun, so the finish
     * gaps and the elapsed gaps are the same numbers and a test built on it would pass
     * whichever quantity the engine used. Here the slow boat starts first and sails
     * longest, so ranking by elapsed and ranking by finish give different answers.
     */
    private static Map<String, Result> pursuit(List<Sailing> fleet)
    {
        List<Sailing> byFinish = fleet.stream()
            .sorted((a, b) -> a.finish().compareTo(b.finish())).toList();
        Map<String, Result> out = new LinkedHashMap<>();
        for (int i = 0; i < byFinish.size(); i++)
        {
            Sailing s = byFinish.get(i);
            int gun = (int)LocalTime.parse(s.gun()).toSecondOfDay();
            int fin = (int)LocalTime.parse(s.finish()).toSecondOfDay();
            // actualStart/finish carry the elapsed, exactly as the servlet builds them;
            // the real finish travels separately.
            out.put(s.id(), new Result(s.id(), FinishStatus.FIN, LocalTime.MIDNIGHT,
                LocalTime.MIDNIGHT.plusSeconds(fin - gun), null, i + 1, fin));
        }
        return out;
    }

    /**
     * Slowest-rated boat first off the gun, fastest last — the stagger of wiki §4.
     * They finish 0 / 2 / 5 / 8 / 10 minutes apart, and their elapsed times run the
     * OTHER way, so the two orderings genuinely disagree.
     */
    private static List<Sailing> pursuitFleet()
    {
        return List.of(
            new Sailing("slow", 0.90, "18:00:00", "19:40:00"),   // elapsed 100, delta 10
            new Sailing("s2",   0.95, "18:05:00", "19:38:00"),   // elapsed  93, delta  8
            new Sailing("mid",  1.00, "18:10:00", "19:35:00"),   // elapsed  85, delta  5
            new Sailing("f2",   1.05, "18:15:00", "19:32:00"),   // elapsed  77, delta  2
            new Sailing("fast", 1.10, "18:20:00", "19:30:00"));  // elapsed  70, delta  0
    }

    private static List<Competitor> competitors(List<Sailed> fleet)
    {
        return fleet.stream().map(s -> new Competitor(s.id(), s.tcf())).toList();
    }

    private static List<Sailed> fleet()
    {
        List<Sailed> f = new ArrayList<>();
        f.add(new Sailed("a", 1.00, 80));
        f.add(new Sailed("b", 1.05, 90));
        f.add(new Sailed("c", 0.95, 100));
        f.add(new Sailed("d", 1.10, 110));
        f.add(new Sailed("e", 0.90, 120));
        return f;
    }

    private static Map<String, Adjustment> byId(List<Adjustment> adjustments)
    {
        return adjustments.stream().collect(
            Collectors.toMap(Adjustment::boatId, Function.identity()));
    }

    // --- the invariant that has to hold for every corner of the square ------

    @Test
    void everyVariantRedistributesThePoolInFull()
    {
        for (Variant v : Variant.values())
        {
            List<Adjustment> out = new PursuitHandicapEngine(alg(v))
                .processResults(competitors(fleet()), race(), resultsOf(fleet(), 1.0));
            double sum = out.stream().mapToDouble(Adjustment::netAdjustmentMinutes).sum();
            assertThat("net adjustments must sum to zero for variant " + v,
                sum, closeTo(0.0, 1e-9));
        }
    }

    @Test
    void anIntermediateGammaIsLegalAndAlsoConserves()
    {
        List<Adjustment> out = new PursuitHandicapEngine(alg(PenaltyScaling.PER_HOUR, 0.35))
            .processResults(competitors(fleet()), race(), resultsOf(fleet(), 1.0));
        assertThat(out.stream().mapToDouble(Adjustment::netAdjustmentMinutes).sum(),
            closeTo(0.0, 1e-9));
        // Between the corners: weighted, so the boats do not all get the same.
        double first = out.get(0).rewardMinutes();
        assertThat(out.stream().anyMatch(a -> Math.abs(a.rewardMinutes() - first) > 1e-9),
            is(true));
    }

    // --- knob 2 -------------------------------------------------------------

    @Test
    void gammaZeroSplitsThePoolEvenlyToTheLastDecimal()
    {
        List<Adjustment> out = new PursuitHandicapEngine(alg(Variant.C))
            .processResults(competitors(fleet()), race(), resultsOf(fleet(), 1.0));
        double pool = out.stream().mapToDouble(Adjustment::penaltyMinutes).sum();
        // Evenly over the boats that MAY receive — the three outside the two-rung
        // ladder. The two that paid draw nothing, at every γ.
        double expected = pool / 3;
        Map<String, Adjustment> byId = byId(out);
        for (String id : List.of("c", "d", "e"))
            assertThat(id, byId.get(id).rewardMinutes(), closeTo(expected, 1e-12));
        for (String id : List.of("a", "b"))
            assertThat(id, byId.get(id).rewardMinutes(), closeTo(0.0, 1e-12));
    }

    /**
     * At γ = 1 the pool is shared by how far behind the leader each boat finished.
     *
     * <p>The weight is {@code 1 + gap/maxGap}, not the gap itself. The fleet finishes
     * 0 / 2 / 5 / 8 / 10 minutes apart and the first two are on the ladder, so the three
     * that may receive carry 1.5, 1.8 and 2.0 against a widest gap of ten minutes.
     *
     * <p><b>Not proportional to the gap.</b> A boat twice as far back draws 1.33 times
     * as much, not twice — the weight starts at one for a boat that may receive at all,
     * because it turned up and got round, and the gap adds to that rather than being it.
     * The old form was the bare gap, which handed the boat just outside the ladder
     * almost nothing on a night when the fleet finished close together.
     */
    @Test
    void gammaOneSharesThePoolByHowFarBehindTheLeaderTheyFinished()
    {
        List<Competitor> boats = pursuitFleet().stream()
            .map(s -> new Competitor(s.id(), s.tcf())).toList();
        Map<String, Adjustment> out = byId(new PursuitHandicapEngine(alg(Variant.D))
            .processResults(boats, race(), pursuit(pursuitFleet())));

        double pool = out.values().stream().mapToDouble(Adjustment::penaltyMinutes).sum();
        assertThat("the two on the ladder get nothing back",
            out.get("fast").rewardMinutes(), closeTo(0.0, 1e-12));
        assertThat(out.get("f2").rewardMinutes(), closeTo(0.0, 1e-12));

        // 1 + 5/10, 1 + 8/10, 1 + 10/10 over their sum.
        assertThat(out.get("mid").rewardMinutes(),  closeTo(pool * 1.5 / 5.3, 1e-9));
        assertThat(out.get("s2").rewardMinutes(),   closeTo(pool * 1.8 / 5.3, 1e-9));
        assertThat(out.get("slow").rewardMinutes(), closeTo(pool * 2.0 / 5.3, 1e-9));

        // Further back is more back, which is the whole of what γ says.
        assertThat(out.get("slow").rewardMinutes() > out.get("s2").rewardMinutes(), is(true));
        assertThat(out.get("s2").rewardMinutes() > out.get("mid").rewardMinutes(), is(true));
    }

    /**
     * The giveback follows the finish gaps and NOT the elapsed times.
     *
     * <p>In this fleet the two run opposite ways: the slow boat sailed longest (100 min)
     * and finished last, the fast boat sailed least (70 min) and won. Under the old
     * elapsed weighting the slow boat drew the largest share for having been on the water
     * longest — which in a pursuit race is a fact about its rating, not its sailing. This
     * pins the difference: if the engine ever went back to elapsed, the leader would stop
     * being the one that gets nothing.
     */
    @Test
    void theGivebackFollowsFinishGapsNotElapsedTimes()
    {
        List<Competitor> boats = pursuitFleet().stream()
            .map(s -> new Competitor(s.id(), s.tcf())).toList();
        Map<String, Adjustment> out = byId(new PursuitHandicapEngine(alg(Variant.D))
            .processResults(boats, race(), pursuit(pursuitFleet())));

        // Elapsed order is slow(100) > s2(93) > mid(85) > f2(77) > fast(70): the exact
        // reverse of the finish order. The shares follow the finish order, among the
        // three that are not on the ladder.
        assertThat(out.get("mid").rewardMinutes() < out.get("s2").rewardMinutes(), is(true));
        assertThat(out.get("s2").rewardMinutes() < out.get("slow").rewardMinutes(), is(true));
        // Were the weighting on elapsed, "slow" would be first in the queue rather than
        // last: it was on the water thirty minutes longer than the winner, because it was
        // given the earliest gun.
        assertThat(out.get("slow").rewardMinutes() > out.get("mid").rewardMinutes(), is(true));

        // …and the whole pool still comes back.
        assertThat(out.values().stream().mapToDouble(Adjustment::netAdjustmentMinutes).sum(),
            closeTo(0.0, 1e-9));
    }

    /**
     * γ is a dial with no step in it: {@code 1 + γ·gap/maxGap}, normalised so the
     * eligible finishers always average one boat. At 0 they all draw the same, at 1 the
     * last boat home draws twice what a boat on the leader's gap would.
     *
     * <p>It is watched at the BACK of the fleet now. It used to be watched at the leader,
     * whose share fell to nothing as γ rose — but the leader is on the ladder and draws
     * nothing at every γ, so that reading has become constant and would pass whatever the
     * dial did.
     */
    @Test
    void gammaIsContinuousFromEvenToGapWeighted()
    {
        List<Competitor> boats = pursuitFleet().stream()
            .map(s -> new Competitor(s.id(), s.tcf())).toList();
        Map<String, Result> results = pursuit(pursuitFleet());

        double previous = 0.0;
        for (double gamma : new double[]{0.0, 0.01, 0.25, 0.5, 0.75, 0.99, 1.0})
        {
            Map<String, Adjustment> out = byId(
                new PursuitHandicapEngine(alg(PenaltyScaling.PER_HOUR, gamma))
                    .processResults(boats, race(), results));
            double pool = out.values().stream().mapToDouble(Adjustment::penaltyMinutes).sum();
            double last = out.get("slow").rewardMinutes();

            // Three eligible boats on gaps 5, 8 and 10 behind a widest gap of 10:
            // raw weights 1+γ/2, 1+0.8γ and 1+γ, and the last of them over their sum.
            double sum = (1 + gamma * 0.5) + (1 + gamma * 0.8) + (1 + gamma);
            assertThat("γ=" + gamma, last, closeTo(pool * (1 + gamma) / sum, 1e-9));
            assertThat("γ=" + gamma + " must not step", last > previous - 1e-12, is(true));
            previous = last;
            assertThat("γ=" + gamma + " conserves",
                out.values().stream().mapToDouble(Adjustment::netAdjustmentMinutes).sum(),
                closeTo(0.0, 1e-9));
        }
    }

    /**
     * A dead heat has no gaps to share by, so the pool is split evenly.
     *
     * <p>Not an error case: with the whole fleet on the same second there is nothing to
     * separate them, and an even split is the only answer that treats them alike.
     */
    @Test
    void aDeadHeatFallsBackToAnEvenSplit()
    {
        List<Sailing> tied = List.of(
            new Sailing("a", 1.0, "18:00:00", "19:30:00"),
            new Sailing("b", 1.0, "18:05:00", "19:30:00"),
            new Sailing("c", 1.0, "18:10:00", "19:30:00"));
        List<Competitor> boats = tied.stream()
            .map(s -> new Competitor(s.id(), s.tcf())).toList();

        // A one-rung ladder, so two of the three may receive — with the file's usual
        // two rungs there would be a single eligible boat and nothing to split.
        JinxConfig.Algorithm oneRung = new JinxConfig.Algorithm(List.of(5.0), 90,
            "18:00", -33.8, 151.2833, false, null, PenaltyScaling.PER_HOUR, 1.0, 1.2, 0.2);
        Map<String, Adjustment> out = byId(new PursuitHandicapEngine(oneRung)
            .processResults(boats, race(), pursuit(tied)));
        double pool = out.values().stream().mapToDouble(Adjustment::penaltyMinutes).sum();
        for (String id : List.of("b", "c"))
            assertThat(id, out.get(id).rewardMinutes(), closeTo(pool / 2.0, 1e-9));
    }

    /**
     * A boat that ran out of time draws the largest single share, and this is how large:
     * {@code dnfWeight} times what the last boat home draws. At the default 1.2 that is
     * a fifth as much again.
     *
     * <p><b>Said as a weight, not as an allowance.</b> It used to be expressed by scoring
     * a DNF at the last finisher plus {@code dnfAllowance} minutes and then sharing by
     * the gap — so what a retirement actually drew depended on how spread out the fleet
     * was that night. Five minutes was a nudge against a ninety-minute race and half the
     * fleet's spread again against a ten-minute finish, which is why the allowance had to
     * come down to one and why the right value was never obvious. A weight has no such
     * scale in it: 1.2 boats is 1.2 boats on any night.
     *
     * <p>The shape is intended and should stay: the boat that could not get round in the
     * time is the one whose handicap should ease most.
     *
     * <p>It holds at <b>every</b> γ, because the weight is {@code lastHome + (dnfWeight −
     * 1)} — a constant margin above the furthest-behind finisher rather than a multiple of
     * it. So the ratio to the last boat home is {@code (dnfWeight + γ) / (1 + γ)}: the
     * headline 1.2× at the club's γ = 0, narrowing as the dial rises because the finisher
     * it is measured against is itself worth more. See
     * {@link #aRetirementOutdrawsEveryFinisherAtEveryGamma}.
     */
    @Test
    void aBoatThatRanOutOfTimeDrawsTheLargestShareAndThisIsHowLarge()
    {
        List<Sailing> raced = pursuitFleet();
        Map<String, Result> results = new LinkedHashMap<>(pursuit(raced));
        results.put("quit", new Result("quit", FinishStatus.DNF, null, null, null));

        List<Competitor> boats = new ArrayList<>(raced.stream()
            .map(s -> new Competitor(s.id(), s.tcf())).toList());
        boats.add(new Competitor("quit", 1.0));

        for (double gamma : new double[] {0.0, 0.5, 1.0})
        {
            Map<String, Adjustment> out = byId(new PursuitHandicapEngine(
                alg(PenaltyScaling.PER_HOUR, gamma)).processResults(boats, race(), results));

            // The last boat home is worth 1 + γ; the retirement is worth that plus 0.2.
            // At γ = 0 that reads as the headline "a fifth again"; above it the ratio
            // narrows, because the boat it is measured against is itself worth more.
            assertThat("γ=" + gamma, out.get("quit").rewardMinutes(),
                closeTo((1.2 + gamma) / (1.0 + gamma) * out.get("slow").rewardMinutes(), 1e-9));

            // Still the largest single share, which is the intended shape.
            for (String id : List.of("fast", "f2", "mid", "s2", "slow"))
                assertThat("γ=" + gamma + " vs " + id,
                    out.get("quit").rewardMinutes() > out.get(id).rewardMinutes(), is(true));

            // And the pool still balances.
            assertThat("γ=" + gamma,
                out.values().stream().mapToDouble(Adjustment::netAdjustmentMinutes).sum(),
                closeTo(0.0, 1e-9));
        }
    }

    /** The same weight, read off the other end: turn it down and the share follows. */
    @Test
    void theDnfWeightIsWhatDecidesHowMuchARetirementDraws()
    {
        List<Sailing> raced = pursuitFleet();
        Map<String, Result> results = new LinkedHashMap<>(pursuit(raced));
        results.put("quit", new Result("quit", FinishStatus.DNF, null, null, null));
        List<Competitor> boats = new ArrayList<>(raced.stream()
            .map(s -> new Competitor(s.id(), s.tcf())).toList());
        boats.add(new Competitor("quit", 1.0));

        Map<String, Adjustment> level = byId(new PursuitHandicapEngine(
            alg(PenaltyScaling.PER_HOUR, 0.0, 1.0, 0.2)).processResults(boats, race(), results));
        assertThat("at 1.0 it draws exactly what the last boat home draws",
            level.get("quit").rewardMinutes(),
            closeTo(level.get("slow").rewardMinutes(), 1e-9));
    }

    // --- knob 1: what a penalty is measured against ---------------------------

    /**
     * A flat penalty gives the same correction whatever the night did.
     *
     * <p>The §7 denominator is the race's <em>expected</em> duration, which is a
     * pre-race number and does not move when the fleet takes twice as long. So under
     * fixed penalties nothing in the arithmetic depends on how the night actually went,
     * and the same finish order produces the same handicap change either way.
     *
     * <p>This is the reverse of what the engine used to do. The denominator was the
     * measured median, so a flat penalty against a longer race came out as a smaller
     * correction, and only the per-hour scaling cancelled it back out. Both quantities
     * moved to what the committee asked for: the penalty to the boat's own time on the
     * course, the denominator to the estimate for the race the TCF will next be used on.
     */
    @Test
    void aFixedPenaltyGivesTheSameCorrectionHoweverLongTheNightWas()
    {
        List<Competitor> boats = competitors(fleet());
        PursuitHandicapEngine engine = new PursuitHandicapEngine(alg(Variant.A));

        Map<String, Adjustment> normal = byId(
            engine.processResults(boats, race(), resultsOf(fleet(), 1.0)));
        Map<String, Adjustment> doubled = byId(
            engine.processResults(boats, race(), resultsOf(fleet(), 2.0)));

        for (Competitor b : boats)
        {
            assertThat("a flat penalty must not care how long the race took, for "
                + b.boatId(), pctChange(doubled.get(b.boatId())),
                closeTo(pctChange(normal.get(b.boatId())), 1e-9));
        }
    }

    /**
     * A per-hour penalty doubles when the boat takes twice as long, because it is a rate
     * and the rate is charged against that boat's own time on the course.
     *
     * <p>Against the boat's own elapsed, not the fleet's median: a boat that was out
     * there for two hours has earned twice the penalty of one that was out for one, and
     * the median said nothing about either of them. With the denominator now fixed at the
     * expected duration there is nothing left to cancel it, so a long night is a larger
     * correction — which is what "per hour" means.
     */
    @Test
    void aPerHourPenaltyScalesWithTheBoatsOwnTimeOnTheCourse()
    {
        List<Competitor> boats = competitors(fleet());
        PursuitHandicapEngine engine = new PursuitHandicapEngine(alg(Variant.C));

        Map<String, Adjustment> normal = byId(
            engine.processResults(boats, race(), resultsOf(fleet(), 1.0)));
        Map<String, Adjustment> doubled = byId(
            engine.processResults(boats, race(), resultsOf(fleet(), 2.0)));

        for (Competitor b : boats)
        {
            assertThat("a rate must follow the boat's own elapsed, for " + b.boatId(),
                correctionTerm(doubled.get(b.boatId())),
                closeTo(correctionTerm(normal.get(b.boatId())) * 2.0, 1e-12));
        }
        // …and it is a larger correction, not merely a different one. Not strictly
        // larger for every boat: with five penalties split evenly five ways the third
        // boat pays back exactly what it takes, so its net is zero whatever the rate.
        assertThat(boats.stream().anyMatch(b ->
            Math.abs(pctChange(doubled.get(b.boatId())))
                > Math.abs(pctChange(normal.get(b.boatId())))), is(true));
    }

    private static double pctChange(Adjustment a)
    {
        return (a.newTcf() - a.oldTcf()) / a.oldTcf() * 100.0;
    }

    /** The x in {@code newTcf = oldTcf / (1 − x)}. */
    private static double correctionTerm(Adjustment a)
    {
        return 1.0 - a.oldTcf() / a.newTcf();
    }

    @Test
    void perHourReadsThePenaltyListAsARatePerHour()
    {
        // Each boat's own time on the course, not the fleet's median: "a" sailed 80
        // minutes and "b" sailed 90, so their 5.0 and 4.0 become 5 × 80/60 and 4 × 90/60.
        // Under the old median-based scaling both were charged against 100.
        Map<String, Adjustment> out = byId(new PursuitHandicapEngine(alg(Variant.C))
            .processResults(competitors(fleet()), race(), resultsOf(fleet(), 1.0)));
        assertThat(out.get("a").penaltyMinutes(), closeTo(5.0 * 80.0 / 60.0, 1e-9));
        assertThat(out.get("b").penaltyMinutes(), closeTo(4.0 * 90.0 / 60.0, 1e-9));

        Map<String, Adjustment> fixed = byId(new PursuitHandicapEngine(alg(Variant.A))
            .processResults(competitors(fleet()), race(), resultsOf(fleet(), 1.0)));
        assertThat(fixed.get("a").penaltyMinutes(), closeTo(5.0, 1e-9));
        assertThat(fixed.get("b").penaltyMinutes(), closeTo(4.0, 1e-9));
    }

    @Test
    void aPlaceBeyondTheListCostsNothing()
    {
        List<Sailed> big = new ArrayList<>(fleet());
        big.add(new Sailed("f", 1.0, 130));
        big.add(new Sailed("g", 1.0, 140));
        Map<String, Adjustment> out = byId(new PursuitHandicapEngine(alg(Variant.C))
            .processResults(competitors(big), race(), resultsOf(big, 1.0)));
        assertThat(out.get("f").penaltyMinutes(), closeTo(0.0, 1e-12));
        assertThat(out.get("g").penaltyMinutes(), closeTo(0.0, 1e-12));
        // …but they still draw from the pool, which is the point of the giveback.
        assertThat(out.get("g").rewardMinutes() > 0.0, is(true));
    }

    // --- who is in the arithmetic at all ------------------------------------

    /**
     * A casual is handicapped, but does not disturb anybody else's handicap.
     *
     * <p>Two passes. The first excludes the casuals and is the answer for every series
     * entrant. The second includes everybody and is the answer for the casuals alone.
     * A casual therefore gets a real TCF adjustment — it sailed, and next time it turns
     * up its handicap should reflect that — while the series boats are scored on the
     * race their own series had.
     */
    @Test
    void aCasualIsHandicappedWithoutDisturbingTheSeriesEntrants()
    {
        List<Sailed> withCasual = new ArrayList<>(fleet());
        withCasual.add(new Sailed("casual", 1.0, 70));   // wins on the water

        List<Competitor> boats = new ArrayList<>(competitors(fleet()));
        boats.add(new Competitor("casual", 1.0, false));

        PursuitHandicapEngine engine = new PursuitHandicapEngine(alg(Variant.C));
        Map<String, Adjustment> mixed = byId(
            engine.processResults(boats, race(), resultsOf(withCasual, 1.0)));
        Map<String, Adjustment> alone = byId(
            engine.processResults(competitors(fleet()), race(), resultsOf(fleet(), 1.0)));

        // The series entrants are scored exactly as if the casual had stayed home.
        for (String id : List.of("a", "b", "c", "d", "e"))
        {
            assertThat("casual must not move " + id,
                mixed.get(id).newTcf(), closeTo(alone.get(id).newTcf(), 1e-12));
            assertThat(mixed.get(id).penaltyMinutes(),
                closeTo(alone.get(id).penaltyMinutes(), 1e-12));
            assertThat(mixed.get(id).rewardMinutes(),
                closeTo(alone.get(id).rewardMinutes(), 1e-12));
        }

        // …and the casual is handicapped rather than frozen.
        Adjustment c = mixed.get("casual");
        assertThat(c.newTcf(), not(equalTo(c.oldTcf())));
        assertThat(c.penaltyMinutes() > 0.0, is(true));
    }

    /**
     * The visible consequence, and the intended one: when a casual wins, the top penalty
     * is awarded twice — once to the casual, and once to the first series boat home,
     * which won its own race.
     */
    @Test
    void aWinningCasualDoesNotCostTheFirstSeriesBoatItsPenalty()
    {
        List<Sailed> withCasual = new ArrayList<>(fleet());
        withCasual.add(new Sailed("casual", 1.0, 70));

        List<Competitor> boats = new ArrayList<>(competitors(fleet()));
        boats.add(new Competitor("casual", 1.0, false));

        Map<String, Adjustment> out = byId(new PursuitHandicapEngine(alg(Variant.A))
            .processResults(boats, race(), resultsOf(withCasual, 1.0)));

        // Fixed scaling, so the ladder reads as the plain figures.
        assertThat(out.get("casual").penaltyMinutes(), closeTo(5.0, 1e-9));
        assertThat(out.get("a").penaltyMinutes(), closeTo(5.0, 1e-9));
        // …and the rest of the series fleet is unshifted, down the two-rung ladder.
        assertThat(out.get("b").penaltyMinutes(), closeTo(4.0, 1e-9));
        for (String id : List.of("c", "d", "e"))
            assertThat(id, out.get(id).penaltyMinutes(), closeTo(0.0, 1e-9));
    }

    /**
     * Conservation holds within each pass, and therefore over the series entrants — but
     * NOT over the merged answer once a casual is in it. That is inherent: the casual's
     * numbers come from a race the series boats were not scored on, so the two halves do
     * not add up. Asserted here so nobody "fixes" it by feeding the casual's residue back
     * into the series fleet, which is precisely what the two passes exist to prevent.
     */
    @Test
    void conservationHoldsPerPassNotAcrossTheMergedAnswer()
    {
        List<Sailed> withCasual = new ArrayList<>(fleet());
        withCasual.add(new Sailed("casual", 1.0, 70));

        List<Competitor> boats = new ArrayList<>(competitors(fleet()));
        boats.add(new Competitor("casual", 1.0, false));

        List<Adjustment> out = new PursuitHandicapEngine(alg(Variant.C))
            .processResults(boats, race(), resultsOf(withCasual, 1.0));

        double seeded = out.stream().filter(a -> !a.boatId().equals("casual"))
            .mapToDouble(Adjustment::netAdjustmentMinutes).sum();
        assertThat("the series fleet redistributes in full", seeded, closeTo(0.0, 1e-9));

        double all = out.stream().mapToDouble(Adjustment::netAdjustmentMinutes).sum();
        assertThat("the casual's share is extra, by design",
            Math.abs(all) > 1e-6, is(true));
    }

    @Test
    void everySeededBoatIsAnsweredExactlyOnce()
    {
        List<Sailed> withCasuals = new ArrayList<>(fleet());
        withCasuals.add(new Sailed("c1", 1.0, 70));
        withCasuals.add(new Sailed("c2", 1.0, 130));

        List<Competitor> boats = new ArrayList<>(competitors(fleet()));
        boats.add(new Competitor("c1", 1.0, false));
        boats.add(new Competitor("c2", 1.0, false));

        List<Adjustment> out = new PursuitHandicapEngine(alg(Variant.C))
            .processResults(boats, race(), resultsOf(withCasuals, 1.0));

        assertThat(out.size(), equalTo(7));
        assertThat(out.stream().map(Adjustment::boatId).distinct().count(), equalTo(7L));
        // A casual that finished last still draws from the pool of the race it was in.
        assertThat(out.stream().filter(a -> a.boatId().equals("c2"))
            .findFirst().orElseThrow().rewardMinutes() > 0.0, is(true));
    }

    /**
     * A retirement is frozen: no penalty, no share of the pool, TCF untouched.
     *
     * <p>DNF and RET are not the same thing and must not be scored the same way. A boat
     * that is <b>DNF</b> was still racing when the race ended — it ran out of time, which
     * is a statement about its speed, so its handicap should ease. A boat that
     * <b>retired</b> stopped for a reason that has nothing to do with its rating: gear
     * broke, someone was hurt, they had to be somewhere. Easing its handicap for that
     * would hand it a better start next week for having had a bad night, and repeated
     * retirements would ratchet a boat's rating down without it ever sailing a race.
     *
     * <p>So RET sits with DSQ, DNC and DNS: out of the placings, out of the giveback, out
     * of the measured duration, and out of the arithmetic entirely.
     */
    @Test
    void aRetirementIsFrozenBecauseItSaysNothingAboutTheBoatsSpeed()
    {
        List<Sailing> raced = pursuitFleet();
        Map<String, Result> results = new LinkedHashMap<>(pursuit(raced));
        results.put("gear", new Result("gear", FinishStatus.RET, null, null, null));

        List<Competitor> boats = new ArrayList<>(raced.stream()
            .map(s -> new Competitor(s.id(), s.tcf())).toList());
        boats.add(new Competitor("gear", 1.0));

        Map<String, Adjustment> out = byId(new PursuitHandicapEngine(alg(Variant.D))
            .processResults(boats, race(), results));

        Adjustment ret = out.get("gear");
        assertThat("a retirement keeps its handicap", ret.newTcf(), equalTo(ret.oldTcf()));
        assertThat(ret.rewardMinutes(), closeTo(0.0, 1e-12));
        assertThat(ret.penaltyMinutes(), closeTo(0.0, 1e-12));
        assertThat(ret.finishPosition(), is((Integer)null));

        // …and it takes nothing from the boats that did race: they are scored exactly as
        // if it had stayed on the mooring.
        Map<String, Adjustment> without = byId(new PursuitHandicapEngine(alg(Variant.D))
            .processResults(raced.stream().map(s -> new Competitor(s.id(), s.tcf())).toList(),
                race(), pursuit(raced)));
        for (Sailing sail : raced)
        {
            assertThat("retirement must not move " + sail.id(),
                out.get(sail.id()).newTcf(), closeTo(without.get(sail.id()).newTcf(), 1e-12));
        }
    }

    /**
     * A DNF is not frozen — it ran out of time, and that is about its speed.
     *
     * <p>The pair with {@link #aRetirementIsFrozenBecauseItSaysNothingAboutTheBoatsSpeed}:
     * these two statuses used to be handled identically and now differ, so both halves
     * are pinned.
     */
    @Test
    void aDnfIsStillHandicappedBecauseRunningOutOfTimeIsAboutSpeed()
    {
        List<Sailing> raced = pursuitFleet();
        Map<String, Result> results = new LinkedHashMap<>(pursuit(raced));
        results.put("slowcoach", new Result("slowcoach", FinishStatus.DNF, null, null, null));

        List<Competitor> boats = new ArrayList<>(raced.stream()
            .map(s -> new Competitor(s.id(), s.tcf())).toList());
        boats.add(new Competitor("slowcoach", 1.0));

        Adjustment dnf = byId(new PursuitHandicapEngine(alg(Variant.D))
            .processResults(boats, race(), results)).get("slowcoach");

        assertThat(dnf.rewardMinutes() > 0.0, is(true));
        assertThat("its handicap eases", dnf.newTcf() < dnf.oldTcf(), is(true));
    }

    /**
     * A boat that never came draws a share, and this is the test that used to say the
     * opposite.
     *
     * <p>It was frozen — no penalty, no share, TCF untouched — and that was the whole
     * cause of the failure this scheme was written for. The pool was divided among the
     * boats that raced, so it went as 1/turnout: thirty boats out and the fifteen minutes
     * spread thirty ways, five boats out and the same fifteen minutes came straight back
     * to the five that had just been charged it.
     *
     * <p>So DNC now carries a weight of {@code dncWeight × (stayed home / entered)} — a
     * fraction of an ordinary finisher, rising as the fleet empties. It still pays
     * nothing and it still has no place; what it no longer does is stand outside the
     * arithmetic while the boats that turned up hand the pool back to each other.
     */
    @Test
    void aDncBoatDrawsAShareWithoutChangingAnybodysPenalty()
    {
        List<Competitor> boats = new ArrayList<>(competitors(fleet()));
        boats.add(new Competitor("ghost", 1.0));
        Map<String, Result> results = new LinkedHashMap<>(resultsOf(fleet(), 1.0));
        results.put("ghost", new Result("ghost", FinishStatus.DNC, null, null, null));

        Map<String, Adjustment> out = byId(new PursuitHandicapEngine(alg(Variant.C))
            .processResults(boats, race(), results));

        // One of six entered stayed home, so it is worth 0.2 × 1/6 of a finisher.
        double perFinisher = out.get("c").rewardMinutes();
        assertThat(out.get("ghost").rewardMinutes(),
            closeTo(perFinisher * 0.2 / 6.0, 1e-9));
        assertThat("its handicap eases, a little",
            out.get("ghost").newTcf() < out.get("ghost").oldTcf(), is(true));
        assertThat(out.get("ghost").penaltyMinutes(), closeTo(0.0, 1e-12));
        assertThat(out.get("ghost").finishPosition(), is((Integer) null));

        // A boat that never came still cannot change what anybody else is CHARGED: the
        // winner's penalty is a rate against its own 80 minutes, and nobody else's night
        // can reach it.
        assertThat(out.get("a").penaltyMinutes(), closeTo(5.0 * 80.0 / 60.0, 1e-9));
    }

    @Test
    void retirementsDrawFromThePoolWithoutChangingAnybodysPenalty()
    {
        List<Competitor> boats = new ArrayList<>(competitors(fleet()));
        boats.add(new Competitor("quit1", 1.0));
        boats.add(new Competitor("quit2", 1.0));
        boats.add(new Competitor("quit3", 1.0));
        Map<String, Result> results = new LinkedHashMap<>(resultsOf(fleet(), 1.0));
        // DNF, not RET: a retirement is frozen and would never reach the median at all.
        for (String id : List.of("quit1", "quit2", "quit3"))
            results.put(id, new Result(id, FinishStatus.DNF, null, null, null));

        // Three boats running out of time does not make the winner's penalty larger.
        // It used to be able to: the penalty was a rate against the fleet's median
        // elapsed, and a retirement's allowance-derived time could join that sample —
        // so a hard night charged the boats that finished it more. A rate against the
        // boat's own time cannot be reached by anybody else's night at all.
        Map<String, Adjustment> off = byId(new PursuitHandicapEngine(alg(Variant.C))
            .processResults(boats, race(), results));
        assertThat(off.get("a").penaltyMinutes(), closeTo(5.0 * 80.0 / 60.0, 1e-9));

        // Boats that ran out of time still draw from the pool — they sailed.
        assertThat(off.get("quit1").rewardMinutes() > 0.0, is(true));
        assertThat(off.get("quit1").penaltyMinutes(), closeTo(0.0, 1e-12));
    }

    @Test
    void aRaceWithNobodyHomeChangesNoHandicaps()
    {
        List<Competitor> boats = competitors(fleet());
        Map<String, Result> none = new LinkedHashMap<>();
        for (Competitor b : boats)
            none.put(b.boatId(), new Result(b.boatId(), FinishStatus.DNC, null, null, null));

        List<Adjustment> out = new PursuitHandicapEngine(alg(Variant.C))
            .processResults(boats, race(), none);
        for (Adjustment a : out)
            assertThat(a.newTcf(), equalTo(a.oldTcf()));
    }

    /**
     * A time penalty becomes a TCF change by being measured against the race's
     * <b>expected</b> duration — the estimate, not what the fleet actually took.
     *
     * <p>This is a deliberate reversal. The engine used to run on the measured median on
     * the grounds that an estimate is a guess; the committee's point is that the number
     * being computed is a handicap for the <em>next</em> race, and the next race is far
     * more likely to run close to its expected duration than to the duration of the one
     * just sailed. A night that overran because the breeze died should not shrink every
     * correction the season makes.
     *
     * <p>So a race with a 30-minute target and one with a 240-minute target give
     * different answers from identical sailing, and the shorter target gives the larger
     * correction: the same penalty minutes are a bigger share of a shorter race.
     */
    @Test
    void theExpectedDurationIsWhatAPenaltyIsMeasuredAgainst()
    {
        List<Competitor> boats = competitors(fleet());
        Map<String, Result> results = resultsOf(fleet(), 1.0);
        // Fixed penalties, so the only thing separating the two runs is the denominator.
        PursuitHandicapEngine engine = new PursuitHandicapEngine(alg(Variant.A));

        Race shortTarget = new Race("r1", "s1", 1, "R1", LocalDate.of(2026, 5, 1),
            LocalTime.of(18, 0), 30, false);
        Race longTarget = new Race("r1", "s1", 1, "R1", LocalDate.of(2026, 5, 1),
            LocalTime.of(18, 0), 240, false);

        Map<String, Adjustment> s = byId(engine.processResults(boats, shortTarget, results));
        Map<String, Adjustment> l = byId(engine.processResults(boats, longTarget, results));

        // Eight times the target, so exactly an eighth of the correction term.
        for (Competitor b : boats)
        {
            assertThat(correctionTerm(l.get(b.boatId())),
                closeTo(correctionTerm(s.get(b.boatId())) / 8.0, 1e-12));
        }
        assertThat(boats.stream().anyMatch(b ->
            Math.abs(pctChange(s.get(b.boatId())))
                > Math.abs(pctChange(l.get(b.boatId())))), is(true));
    }

    /**
     * A race that does not carry a target falls back to the club's default duration.
     *
     * <p>Every race the app creates gets one, but a race edited by hand or imported from
     * an older store might not — and the alternative to a fallback is a division by zero
     * in the middle of processing a night's results.
     */
    @Test
    void aRaceWithNoTargetUsesTheConfiguredDefault()
    {
        List<Competitor> boats = competitors(fleet());
        Map<String, Result> results = resultsOf(fleet(), 1.0);
        PursuitHandicapEngine engine = new PursuitHandicapEngine(alg(Variant.A));

        // alg() carries defaultRaceDuration = 90.
        Race noTarget = new Race("r1", "s1", 1, "R1", LocalDate.of(2026, 5, 1),
            LocalTime.of(18, 0), null, false);
        Race ninety = new Race("r1", "s1", 1, "R1", LocalDate.of(2026, 5, 1),
            LocalTime.of(18, 0), 90, false);

        Map<String, Adjustment> fallback = byId(engine.processResults(boats, noTarget, results));
        Map<String, Adjustment> stated = byId(engine.processResults(boats, ninety, results));
        for (Competitor b : boats)
        {
            assertThat(fallback.get(b.boatId()).newTcf(),
                closeTo(stated.get(b.boatId()).newTcf(), 1e-12));
        }
    }

    // --- givebackFleet: who the pool comes back to ------------------------------
    //
    // The pool has always come back to everyone who sailed. The committee wants it aimed
    // at the back of the fleet instead: 1.0 is the whole fleet, 0.33 the bottom third,
    // and 0 keeps it. "Bottom" is by finish gap — furthest behind the first boat home —
    // which is the same quantity the weighting already shares by.

    /** A four-boat pursuit fleet finishing 0, 5, 10 and 15 minutes apart. */
    private static Map<String, Result> spreadOfFour()
    {
        return pursuit(List.of(
            new Sailing("first",  1.00, "18:15:00", "19:30:00"),
            new Sailing("second", 1.00, "18:10:00", "19:35:00"),
            new Sailing("third",  1.00, "18:05:00", "19:40:00"),
            new Sailing("last",   1.00, "18:00:00", "19:45:00")));
    }

    private static List<Competitor> fourBoats()
    {
        return List.of(new Competitor("first", 1.00), new Competitor("second", 1.00),
            new Competitor("third", 1.00), new Competitor("last", 1.00));
    }

    /**
     * A fleet no bigger than the penalty list is charged nothing at all.
     *
     * <p>Four boats and {@code [5,4,3,2,1]}: every boat that raced is on the ladder, so
     * every boat carries weight zero, and there is nobody at home to catch the pool
     * either. Keeping it would move the whole fleet's handicaps against a fleet that is
     * not there, and wiki §9 has always promised a boat racing alone finishes the night
     * where it started.
     *
     * <p>The club's answer to a series this small is a shorter {@code penaltyList} — the
     * ladder wants to be about a sixth of the fleet — and the series form says so. The
     * engine's job is only to refuse to invent a number.
     */
    @Test
    void aFleetNoBiggerThanThePenaltyListIsChargedNothing()
    {
        JinxConfig.Algorithm longLadder = new JinxConfig.Algorithm(
            List.of(5.0, 4.0, 3.0, 2.0, 1.0), 90, "18:00", -33.8, 151.2833, false,
            null, PenaltyScaling.FIXED, 0.0, 1.2, 0.2);
        Map<String, Adjustment> out = byId(new PursuitHandicapEngine(longLadder)
            .processResults(fourBoats(), race(), spreadOfFour()));

        for (Adjustment a : out.values())
        {
            assertThat(a.boatId(), a.penaltyMinutes(), closeTo(0.0, 1e-9));
            assertThat(a.boatId(), a.rewardMinutes(), closeTo(0.0, 1e-9));
            assertThat(a.boatId(), a.newTcf(), closeTo(a.oldTcf(), 1e-12));
        }
    }

    /**
     * The same four boats, scored on a ladder that fits them: only the winner pays, and
     * the three behind it share the five minutes.
     *
     * <p>This is the pair to the test above — the fix for a small fleet is the length of
     * the list, not anything in the arithmetic.
     */
    @Test
    void aLadderThatFitsTheFleetBehavesNormally()
    {
        JinxConfig.Algorithm shortLadder = new JinxConfig.Algorithm(List.of(5.0), 90,
            "18:00", -33.8, 151.2833, false, null, PenaltyScaling.FIXED, 0.0, 1.2, 0.2);
        Map<String, Adjustment> out = byId(new PursuitHandicapEngine(shortLadder)
            .processResults(fourBoats(), race(), spreadOfFour()));

        assertThat(out.get("first").penaltyMinutes(), closeTo(5.0, 1e-9));
        assertThat(out.get("first").rewardMinutes(), closeTo(0.0, 1e-9));
        for (String id : List.of("second", "third", "last"))
            assertThat(id, out.get(id).rewardMinutes(), closeTo(5.0 / 3.0, 1e-9));
        assertThat(out.values().stream().mapToDouble(Adjustment::netAdjustmentMinutes).sum(),
            closeTo(0.0, 1e-9));
    }

    /**
     * A retirement outdraws every boat that got round, at both ends of the γ dial.
     *
     * <p>This is the test that made the choice visible. Two readings of {@code dnfWeight}
     * were tried and each broke one of the two properties the dial has to keep:
     *
     * <ul>
     *   <li><b>Flat.</b> 1.2 boats whatever γ says — the reading that lets γ hand its
     *       extra weight to the finishers alone. But γ adds up to a whole boat to a
     *       finisher's weight and a flat 1.2 does not move, so at about γ = 0.2 the boats
     *       furthest behind overtake the DNF and <b>this test fails</b>.</li>
     *   <li><b>{@code dnfWeight × lastHome}.</b> This test passes, but the DNF then takes
     *       γ's bonus in proportion too and cancels the shift the dial exists to make —
     *       the finishers' total goes flat across it, and
     *       {@code GivebackWeightsTest.theProportionalWeightingMovesThePoolTowardsTheBoatsThatRaced}
     *       fails instead.</li>
     * </ul>
     *
     * <p>{@code lastHome + (dnfWeight − 1)} is the form that satisfies both: a constant
     * margin above the furthest-behind finisher, so a retirement always draws most, while
     * γ's extra boat still reaches the boats that got round. All three agree at γ = 0.
     */
    @Test
    void aRetirementOutdrawsEveryFinisherAtEveryGamma()
    {
        List<Sailing> raced = pursuitFleet();
        Map<String, Result> results = new LinkedHashMap<>(pursuit(raced));
        results.put("quit", new Result("quit", FinishStatus.DNF, null, null, null));
        List<Competitor> boats = new ArrayList<>(raced.stream()
            .map(s -> new Competitor(s.id(), s.tcf())).toList());
        boats.add(new Competitor("quit", 1.0));

        for (double gamma : new double[] {0.0, 0.2, 0.5, 1.0})
        {
            Map<String, Adjustment> out = byId(new PursuitHandicapEngine(
                alg(PenaltyScaling.PER_HOUR, gamma)).processResults(boats, race(), results));
            for (String id : List.of("fast", "f2", "mid", "s2", "slow"))
                assertThat("γ=" + gamma + " vs " + id,
                    out.get("quit").rewardMinutes() > out.get(id).rewardMinutes(), is(true));
            assertThat("γ=" + gamma + " conserves",
                out.values().stream().mapToDouble(Adjustment::netAdjustmentMinutes).sum(),
                closeTo(0.0, 1e-9));
        }
    }
}
