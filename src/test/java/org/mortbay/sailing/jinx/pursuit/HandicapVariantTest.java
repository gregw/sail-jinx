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
    private static final List<Double> PENALTIES = List.of(5.0, 4.0, 3.0, 2.0, 1.0);

    private static JinxConfig.Algorithm alg(PenaltyScaling scaling, double gamma)
    {
        return new JinxConfig.Algorithm(PENALTIES, 90, 5, "18:00", -33.8, 151.2833,
            false, null, scaling, gamma, false);
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

    private static Map<String, Result> resultsOf(List<Sailed> fleet, double scale)
    {
        // Finish order is elapsed order here: shortest elapsed is first across the line.
        List<Sailed> byElapsed = fleet.stream()
            .sorted((a, b) -> Double.compare(a.minutes(), b.minutes())).toList();
        Map<String, Result> out = new LinkedHashMap<>();
        for (int i = 0; i < byElapsed.size(); i++)
        {
            Sailed s = byElapsed.get(i);
            long secs = Math.round(s.minutes() * scale * 60.0);
            out.put(s.id(), new Result(s.id(), FinishStatus.FIN, LocalTime.MIDNIGHT,
                LocalTime.MIDNIGHT.plusSeconds(secs), null, i + 1));
        }
        return out;
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
        double expected = pool / 5;
        for (Adjustment a : out)
            assertThat(a.rewardMinutes(), closeTo(expected, 1e-12));
    }

    @Test
    void gammaOneWeightsTheGivebackByTimeOnTheCourse()
    {
        Map<String, Adjustment> out = byId(new PursuitHandicapEngine(alg(Variant.D))
            .processResults(competitors(fleet()), race(), resultsOf(fleet(), 1.0)));
        // 80 … 120 minutes, so the giveback runs the same way.
        assertThat(out.get("a").rewardMinutes() < out.get("b").rewardMinutes(), is(true));
        assertThat(out.get("b").rewardMinutes() < out.get("c").rewardMinutes(), is(true));
        assertThat(out.get("d").rewardMinutes() < out.get("e").rewardMinutes(), is(true));
        // Exactly proportional to elapsed at γ = 1.
        double pool = out.values().stream().mapToDouble(Adjustment::penaltyMinutes).sum();
        assertThat(out.get("e").rewardMinutes(), closeTo(pool * 120.0 / 500.0, 1e-9));
    }

    // --- knob 1, and why C is the default -----------------------------------

    /**
     * Stretch the whole race — every boat takes twice as long — and ask what a boat's
     * handicap change is as a percentage.
     *
     * <p>Under C the answer is the same. The penalties double because they are a rate,
     * the §7 denominator doubles because it is the same measured duration, and the two
     * cancel. A club that races a long course one week and a short one the next is not
     * quietly applying a bigger correction on the long night.
     *
     * <p>Under A the penalties are a flat number of minutes while the denominator still
     * doubles, so the correction halves. Neither is wrong; they are different opinions
     * about what a penalty means. C is the default because "the same race, slower" should
     * not mean "a different handicap system".
     */
    @Test
    void perHourWithEvenGivebackIsDurationIndependent()
    {
        List<Competitor> boats = competitors(fleet());
        PursuitHandicapEngine engine = new PursuitHandicapEngine(alg(Variant.C));

        Map<String, Adjustment> normal = byId(
            engine.processResults(boats, race(), resultsOf(fleet(), 1.0)));
        Map<String, Adjustment> doubled = byId(
            engine.processResults(boats, race(), resultsOf(fleet(), 2.0)));

        for (Competitor b : boats)
        {
            double pctNormal = pctChange(normal.get(b.boatId()));
            double pctDoubled = pctChange(doubled.get(b.boatId()));
            assertThat("C must not care how long the race took, for " + b.boatId(),
                pctDoubled, closeTo(pctNormal, 1e-9));
        }
    }

    /**
     * The same doubling under A halves the correction, because the penalties are a flat
     * number of minutes while the denominator they are measured against doubles.
     *
     * <p>What halves <em>exactly</em> is the correction term
     * {@code x = net × tcf / (raceDuration × tcfMed)}, recovered here as
     * {@code 1 − old/new}. The resulting percentage change is {@code x/(1−x)}, which is
     * not linear in x, so it comes out a shade above half — 1.0101% against 2.0408%,
     * not 1.0204%. Asserting on x says what is actually true; asserting half the
     * percentage would only be true to two decimal places and for these numbers.
     */
    @Test
    void fixedPenaltiesHalveTheCorrectionWhenTheRaceTakesTwiceAsLong()
    {
        List<Competitor> boats = competitors(fleet());
        PursuitHandicapEngine engine = new PursuitHandicapEngine(alg(Variant.A));

        Map<String, Adjustment> normal = byId(
            engine.processResults(boats, race(), resultsOf(fleet(), 1.0)));
        Map<String, Adjustment> doubled = byId(
            engine.processResults(boats, race(), resultsOf(fleet(), 2.0)));

        for (Competitor b : boats)
        {
            assertThat("A scales with the race, for " + b.boatId(),
                correctionTerm(doubled.get(b.boatId())),
                closeTo(correctionTerm(normal.get(b.boatId())) / 2.0, 1e-12));
            // And it is a smaller correction, not merely a different one. Not strictly
            // smaller for every boat: with five penalties split evenly five ways the
            // third boat pays back exactly what it takes, so its net is zero and stays
            // zero however long the race was.
            assertThat(Math.abs(pctChange(doubled.get(b.boatId())))
                <= Math.abs(pctChange(normal.get(b.boatId()))), is(true));
        }
        // …and the fleet as a whole did move, so the <= above is not vacuous.
        assertThat(boats.stream().anyMatch(b ->
            Math.abs(pctChange(doubled.get(b.boatId())))
                < Math.abs(pctChange(normal.get(b.boatId())))), is(true));
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
        // Five boats at a median of 100 minutes: the winner's 5.0 becomes 5 × 100/60.
        Map<String, Adjustment> out = byId(new PursuitHandicapEngine(alg(Variant.C))
            .processResults(competitors(fleet()), race(), resultsOf(fleet(), 1.0)));
        assertThat(out.get("a").penaltyMinutes(), closeTo(5.0 * 100.0 / 60.0, 1e-9));
        assertThat(out.get("b").penaltyMinutes(), closeTo(4.0 * 100.0 / 60.0, 1e-9));

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
        // …and the rest of the series fleet is unshifted, down the ladder 4, 3, 2, 1.
        assertThat(out.get("b").penaltyMinutes(), closeTo(4.0, 1e-9));
        assertThat(out.get("c").penaltyMinutes(), closeTo(3.0, 1e-9));
        assertThat(out.get("d").penaltyMinutes(), closeTo(2.0, 1e-9));
        assertThat(out.get("e").penaltyMinutes(), closeTo(1.0, 1e-9));
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

    @Test
    void aDncBoatIsFrozenAndOutOfTheMedian()
    {
        List<Competitor> boats = new ArrayList<>(competitors(fleet()));
        boats.add(new Competitor("ghost", 1.0));
        Map<String, Result> results = new LinkedHashMap<>(resultsOf(fleet(), 1.0));
        results.put("ghost", new Result("ghost", FinishStatus.DNC, null, null, null));

        Map<String, Adjustment> out = byId(new PursuitHandicapEngine(alg(Variant.C))
            .processResults(boats, race(), results));

        assertThat(out.get("ghost").newTcf(), equalTo(1.0));
        assertThat(out.get("ghost").finishPosition(), is((Integer) null));
        // The median is still the finishers' 100, so the penalties are unchanged.
        assertThat(out.get("a").penaltyMinutes(), closeTo(5.0 * 100.0 / 60.0, 1e-9));
    }

    @Test
    void retirementsStayOutOfTheMeasuredDurationUnlessTheSwitchIsOn()
    {
        List<Competitor> boats = new ArrayList<>(competitors(fleet()));
        boats.add(new Competitor("quit1", 1.0));
        boats.add(new Competitor("quit2", 1.0));
        boats.add(new Competitor("quit3", 1.0));
        Map<String, Result> results = new LinkedHashMap<>(resultsOf(fleet(), 1.0));
        for (String id : List.of("quit1", "quit2", "quit3"))
            results.put(id, new Result(id, FinishStatus.RET, null, null, null));

        // Off: the median is the finishers' own, 100 minutes.
        Map<String, Adjustment> off = byId(new PursuitHandicapEngine(alg(Variant.C))
            .processResults(boats, race(), results));
        assertThat(off.get("a").penaltyMinutes(), closeTo(5.0 * 100.0 / 60.0, 1e-9));

        // On: three retirements join the sample at 120 + 5, so eight values
        // (80, 90, 100, 110, 120, 125, 125, 125) put the median at 115 rather than 100.
        JinxConfig.Algorithm withDnf = new JinxConfig.Algorithm(PENALTIES, 90, 5, "18:00",
            -33.8, 151.2833, false, Variant.C, null, null, true);
        Map<String, Adjustment> on = byId(new PursuitHandicapEngine(withDnf)
            .processResults(boats, race(), results));
        assertThat(on.get("a").penaltyMinutes(), closeTo(5.0 * 115.0 / 60.0, 1e-9));

        // Retired boats draw from the pool either way — they sailed.
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
     * The measured duration is the median of what was sailed — never the target. A race
     * that ran to twice its target must be scaled by what happened, not by the estimate.
     */
    @Test
    void theTargetElapsedTimeDoesNotReachTheHandicap()
    {
        List<Competitor> boats = competitors(fleet());
        Map<String, Result> results = resultsOf(fleet(), 1.0);
        PursuitHandicapEngine engine = new PursuitHandicapEngine(alg(Variant.C));

        Race shortTarget = new Race("r1", "s1", 1, "R1", LocalDate.of(2026, 5, 1),
            LocalTime.of(18, 0), 30, false);
        Race longTarget = new Race("r1", "s1", 1, "R1", LocalDate.of(2026, 5, 1),
            LocalTime.of(18, 0), 240, false);

        Map<String, Adjustment> s = byId(engine.processResults(boats, shortTarget, results));
        Map<String, Adjustment> l = byId(engine.processResults(boats, longTarget, results));
        for (Competitor b : boats)
            assertThat(s.get(b.boatId()).newTcf(), closeTo(l.get(b.boatId()).newTcf(), 1e-12));
    }
}
