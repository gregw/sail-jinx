package org.mortbay.sailing.jinx.pursuit;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.mortbay.sailing.jinx.config.JinxConfig;
import org.mortbay.sailing.jinx.model.Adjustment;
import org.mortbay.sailing.jinx.model.FinishStatus;
import org.mortbay.sailing.jinx.model.Race;
import org.mortbay.sailing.jinx.model.Result;
import org.mortbay.sailing.jinx.model.StartTime;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.closeTo;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

/**
 * Executable specification for the pursuit handicap algorithm
 * ({@code wiki/Jinx-Handicaps.md}). Each test maps to a specific section of
 * the algorithm document.
 */
class PursuitHandicapEngineTest
{
    private static final JinxConfig.Algorithm DEFAULT_ALG = new JinxConfig.Algorithm(
        List.of(5.0, 4.0, 3.0, 2.0, 1.0), 90, 1, "18:00", -33.8000, 151.2833, false,
        null, null, null, null);

    private static final double TOLERANCE = 0.01;

    /** The default the club gets when it says nothing: C — per-hour penalties, even giveback. */
    private final PursuitHandicapEngine engine = new PursuitHandicapEngine(DEFAULT_ALG);

    /** A corner of the square this file needs by name rather than by default. */
    private static PursuitHandicapEngine engine(JinxConfig.PenaltyScaling scaling, double gamma)
    {
        return new PursuitHandicapEngine(new JinxConfig.Algorithm(
            List.of(5.0, 4.0, 3.0, 2.0, 1.0), 90, 1, "18:00", -33.8000, 151.2833, false,
            null, scaling, gamma, null));
    }

    /** The pair the engine works on: an id to key the answer by, and the TCF in force. */
    private static Competitor boat(String id, String name, String sailNumber, double tcf)
    {
        return new Competitor(id, tcf);
    }

    /** Race carrying the one input the engine needs: the target elapsed time. */
    private static Race race(int targetElapsedMinutes)
    {
        return new Race("r1", "s1", 1, "R1", LocalDate.of(2026, 5, 1),
            LocalTime.of(18, 0), targetElapsedMinutes, false);
    }

    /**
     * Spec §4: the slowest boat (lowest TCF) starts at {@code t_earliest_start};
     * the fastest boat starts last. Start times are rounded to the nearest minute.
     */
    @Test
    void slowestBoatStartsFirstAtEarliestStart()
    {
        List<Competitor> boats = List.of(
            boat("slow", "Slow Turtle",   "AUS1", 0.8821),
            boat("mid",  "Meridian",      "MYC12", 0.9340),
            boat("fast", "Flashpoint",    "AUS5", 1.0450));
        Race race = race(60);

        List<StartTime> times = engine.computeStartTimes(boats, race);

        assertThat(times, hasSize(3));
        Map<String, StartTime> byId = times.stream()
            .collect(Collectors.toMap(StartTime::boatId, st -> st));
        assertThat(byId.get("slow").startTime(), equalTo(LocalTime.of(18, 0)));
        // fast boat must start strictly after the slow boat
        assertThat(byId.get("fast").startTime().isAfter(byId.get("slow").startTime()),
            equalTo(true));
    }

    /**
     * Spec §6.4: by construction the sum of net adjustments across participating
     * boats is exactly zero — the penalty pool is fully redistributed.
     */
    @Test
    void netAdjustmentsSumToZero()
    {
        List<Competitor> boats = workedExampleFleet();
        Race race = race(90);
        Map<String, Result> results = workedExampleResults();

        List<Adjustment> adjustments = engine.processResults(boats, race, results);

        double sum = adjustments.stream()
            .mapToDouble(Adjustment::netAdjustmentMinutes)
            .sum();
        assertThat(sum, closeTo(0.0, TOLERANCE));
    }

    /**
     * Spec §6.3 at an intermediate γ: half an even split, half shared by the gap behind
     * the leader.
     *
     * <p>γ = 0.5 with fixed penalties, so the pool is a round 15. The worked-example
     * fleet all start together, so the gaps are just the elapsed times less the winner's
     * 85 minutes — 0, 5, 10, 15, 20, 25, 30, and 35 for the DNF (last finisher's 30 plus
     * the 5-minute allowance).
     *
     * <pre>
     *   mean gap = 140 / 8                       = 17.5
     *   wᵢ       = 0.5 × 17.5 + 0.5 × gapᵢ
     *   w(p1)    = 8.75 + 0            = 8.75    (gap 0)
     *   Σw       = 8 × 8.75 + 0.5 × 140          = 140
     *   p1 reward = 15 × 8.75 / 140              = 0.9375
     *   p1 net    = 5 − 0.9375                   = 4.0625
     * </pre>
     *
     * <p>The leader keeps half an even share at γ = 0.5 and loses it smoothly as γ rises
     * — the property the blend exists for. Under the exponent form it would have been
     * zero here, and zero at γ = 0.01 too.
     */
    @Test
    void anIntermediateGammaIsHalfEvenAndHalfGapWeighted()
    {
        List<Competitor> boats = workedExampleFleet();
        Race race = race(90);
        Map<String, Result> results = workedExampleResults();

        Adjustment first = engine(JinxConfig.PenaltyScaling.FIXED, 0.5)
            .processResults(boats, race, results).stream()
            .filter(a -> a.finishPosition() != null && a.finishPosition() == 1)
            .findFirst().orElseThrow();

        assertThat(first.penaltyMinutes(), closeTo(5.0, TOLERANCE));
        assertThat(first.rewardMinutes(), closeTo(0.9375, TOLERANCE));
        assertThat(first.netAdjustmentMinutes(), closeTo(4.0625, TOLERANCE));

        // Exactly half of the even share it would get at γ = 0.
        Adjustment even = engine(JinxConfig.PenaltyScaling.FIXED, 0.0)
            .processResults(boats, race, results).stream()
            .filter(a -> a.finishPosition() != null && a.finishPosition() == 1)
            .findFirst().orElseThrow();
        assertThat(first.rewardMinutes(), closeTo(even.rewardMinutes() / 2.0, TOLERANCE));
    }

    /**
     * Spec §5: DSQ boats are excluded from adjustments — their TCF is frozen.
     */
    @Test
    void dsqBoatTcfIsFrozen()
    {
        List<Competitor> boats = List.of(
            boat("a", "A", "1", 1.0),
            boat("b", "B", "2", 1.0));
        Race race = race(60);
        Map<String, Result> results = Map.of(
            "a", new Result("a", FinishStatus.FIN, LocalTime.of(18, 0), LocalTime.of(19, 0), null),
            "b", new Result("b", FinishStatus.DSQ, LocalTime.of(18, 0), null, null));

        Adjustment b = engine.processResults(boats, race, results).stream()
            .filter(a -> a.boatId().equals("b")).findFirst().orElseThrow();

        assertThat(b.oldTcf(), equalTo(b.newTcf()));
        assertThat(b.netAdjustmentMinutes(), closeTo(0.0, TOLERANCE));
    }

    /**
     * Spec §5: DNF boats share an effective elapsed time of the slowest finisher +
     * dnfAllowance, so they receive identical rewards. RET does not — see
     * {@code HandicapVariantTest.aRetirementIsFrozenBecauseItSaysNothingAboutTheBoatsSpeed}.
     */
    @Test
    void dnfBoatsShareEqualReward()
    {
        List<Competitor> boats = List.of(
            boat("p1", "Pos1", "1", 1.0),
            boat("p2", "Pos2", "2", 1.0),
            boat("dnfA", "DnfA", "3", 1.0),
            boat("dnfB", "DnfB", "4", 1.0));
        Race race = race(90);
        LocalTime start = LocalTime.of(18, 0);
        Map<String, Result> results = Map.of(
            "p1", fin("p1", start, start.plusMinutes(60)),
            "p2", fin("p2", start, start.plusMinutes(80)),
            "dnfA", new Result("dnfA", FinishStatus.DNF, start, null, null),
            "dnfB", new Result("dnfB", FinishStatus.DNF, start, null, null));

        Map<String, Adjustment> byId = engine.processResults(boats, race, results).stream()
            .collect(Collectors.toMap(Adjustment::boatId, a -> a));

        // Both boats ran out of time, so both get the same effective treatment.
        // Deliberately two DNFs and not one of each: a RET is frozen and gets nothing —
        // see aRetirementIsFrozenBecauseItSaysNothingAboutTheBoatsSpeed.
        assertThat(byId.get("dnfA").rewardMinutes(),
            closeTo(byId.get("dnfB").rewardMinutes(), TOLERANCE));
        // Both should have no fixed penalty.
        assertThat(byId.get("dnfA").penaltyMinutes(), closeTo(0.0, TOLERANCE));
        assertThat(byId.get("dnfB").penaltyMinutes(), closeTo(0.0, TOLERANCE));
    }

    /**
     * Spec §7: a positive Δs (penalty) raises TCF; a negative Δs (reward) lowers it.
     * Direction-only check; exact magnitude is governed by the wiki §7 formula.
     */
    @Test
    void positivePenaltyRaisesTcfAndRewardLowersIt()
    {
        List<Competitor> boats = workedExampleFleet();
        Race race = race(90);
        Map<String, Result> results = workedExampleResults();

        Map<String, Adjustment> byId = engine.processResults(boats, race, results).stream()
            .collect(Collectors.toMap(Adjustment::boatId, a -> a));

        // 1st place: penalty 5, reward ~1.71, net +3.29 → TCF goes up.
        assertThat(byId.get("p1").newTcf() > byId.get("p1").oldTcf(), equalTo(true));
        // DNF: penalty 0, reward ~2.03, net −2.03 → TCF goes down.
        assertThat(byId.get("dnf").newTcf() < byId.get("dnf").oldTcf(), equalTo(true));
    }

    /**
     * newTcf is anchored to the race the fleet was <em>set</em> to sail, under the
     * default variant B: fixed penalties, given back by the gap behind the leader.
     *
     * <p>Worked example fleet: 7 finishers @ 85,90,…,115 min + 1 DNF, all TCF = 1.0.
     * The race carries a 90-minute target and the fleet took a median of 100.
     * <pre>
     *   pool         = 5+4+3+2+1                             = 15.0 min   (fixed)
     *   dnf scores   = last finisher + dnfAllowance = 115+1  = 116 min
     *   gaps         = 0,5,10,15,20,25,30 and 31 for the DNF
     *   Σ gaps                                               = 136
     *   p1 reward    = 15.0 × 0 / 136                        = 0.0        (γ = 1)
     *   p1 penalty   = 5                                     = 5.0
     *   p1 net       = 5.0 − 0.0                             = 5.0
     *   scale        = expectedDuration × medianTcf = 90×1.0 = 90
     *   newTcf       = 1.0 / (1 − 5.0 / 90) = 90/85          = 1.058823…
     * </pre>
     *
     * <p><b>The 100 the fleet actually took reaches none of this.</b> That is the
     * reversal the committee asked for: the number being computed is the handicap for
     * the <em>next</em> race, and the next race is far more likely to run close to its
     * expected duration than to the duration of the one just sailed. A night that
     * overran because the breeze died should not shrink every correction the season
     * makes. The engine used to run on the median of what was sailed, and this same
     * fleet came out at 1/0.95 rather than 90/85.
     *
     * <p>Two things it also pins. <b>The winner pays its penalty in full</b> — at γ = 1
     * its gap is zero by definition, so it draws nothing back. And <b>fixed penalties do
     * not move with the night</b>: 5.0 is the penaltyList entry itself, where a per-hour
     * scaling would charge it against this boat's own 85 minutes.
     */
    @Test
    void newTcfIsAnchoredToTheExpectedDurationNotTheMeasuredOne()
    {
        List<Competitor> boats = workedExampleFleet();
        Race race = race(90);
        Map<String, Result> results = workedExampleResults();

        Adjustment p1 = engine.processResults(boats, race, results).stream()
            .filter(a -> a.finishPosition() != null && a.finishPosition() == 1)
            .findFirst().orElseThrow();

        assertThat(p1.newTcf(), closeTo(90.0 / 85.0, 1e-9));
    }

    /**
     * Every gun lands on a whole minute, rounded to the nearest one.
     *
     * <p>The offset from the earliest start has always rounded to nearest — 4.81 minutes
     * is a 5-minute offset, not a 4-minute one. What did not was the earliest start
     * itself: {@code plusMinutes} on an 18:00:40 gun gave every boat in the fleet a
     * start at :40 past, and the pages print {@code HH:MM}, so every one of them
     * displayed 40 seconds early. A gun is a whole minute (wiki §4); it is rounded here
     * so the printed sheet and the stored time cannot disagree.
     */
    @Test
    void everyGunLandsOnAWholeMinute()
    {
        List<Competitor> boats = List.of(
            boat("a", "A", "1", 1.0000),
            boat("b", "B", "2", 0.9480),
            boat("c", "C", "3", 0.9000));

        // 29 seconds stays put, 30 rounds up — the boundary, stated once.
        assertThat(PursuitHandicapEngine.toNearestMinute(LocalTime.of(18, 0, 29)),
            equalTo(LocalTime.of(18, 0)));
        assertThat(PursuitHandicapEngine.toNearestMinute(LocalTime.of(18, 0, 30)),
            equalTo(LocalTime.of(18, 1)));
        assertThat(PursuitHandicapEngine.toNearestMinute(LocalTime.of(18, 0)),
            equalTo(LocalTime.of(18, 0)));

        // An earliest start carrying seconds — from config, or an older stored race.
        Race race = new Race("r1", "s1", 1, "R1", LocalDate.of(2026, 5, 1),
            LocalTime.of(18, 0, 40), 90, false);

        for (StartTime st : engine.computeStartTimes(boats, race))
        {
            assertThat("gun must be a whole minute: " + st.startTime(),
                st.startTime().getSecond(), equalTo(0));
        }

        Map<String, LocalTime> byId = engine.computeStartTimes(boats, race).stream()
            .collect(Collectors.toMap(StartTime::boatId, StartTime::startTime));
        // Median TCF is 0.9480, so tau runs 85.32 / 90.00 / 94.80 and the offsets from
        // the slowest boat are 9.48 / 4.80 / 0. 18:00:40 rounds up to 18:01, and those
        // offsets ride on top of it.
        assertThat(byId.get("c"), equalTo(LocalTime.of(18, 1)));
        assertThat(byId.get("b"), equalTo(LocalTime.of(18, 6)));   // offset 4.80 -> 5
        assertThat(byId.get("a"), equalTo(LocalTime.of(18, 10)));  // offset 9.48 -> 9

        // A gun already on the minute is left exactly where it is.
        Race onTheMinute = new Race("r1", "s1", 1, "R1", LocalDate.of(2026, 5, 1),
            LocalTime.of(18, 0), 90, false);
        Map<String, LocalTime> clean = engine.computeStartTimes(boats, onTheMinute).stream()
            .collect(Collectors.toMap(StartTime::boatId, StartTime::startTime));
        assertThat(clean.get("c"), equalTo(LocalTime.of(18, 0)));
        assertThat(clean.get("b"), equalTo(LocalTime.of(18, 5)));
        assertThat(clean.get("a"), equalTo(LocalTime.of(18, 9)));
    }

    /**
     * Direction-and-magnitude check across the TCF conversion: positive Δs raises TCF,
     * negative lowers it, and DSQ boats stay frozen.
     */
    @Test
    void tcfConversionKeepsDsqFrozenAndPreservesDirection()
    {
        List<Competitor> boats = List.of(
            boat("a", "A", "1", 1.0),
            boat("b", "B", "2", 1.0),
            boat("c", "C", "3", 1.0));
        Race race = race(60);
        LocalTime start = LocalTime.of(18, 0);
        Map<String, Result> results = Map.of(
            "a", fin("a", start, start.plusMinutes(55)),
            "b", fin("b", start, start.plusMinutes(65)),
            "c", new Result("c", FinishStatus.DSQ, start, null, null));

        Map<String, Adjustment> byId = engine.processResults(boats, race, results).stream()
            .collect(Collectors.toMap(Adjustment::boatId, a -> a));

        assertThat(byId.get("a").newTcf() > byId.get("a").oldTcf(), equalTo(true));
        assertThat(byId.get("b").newTcf() < byId.get("b").oldTcf(), equalTo(true));
        assertThat(byId.get("c").oldTcf(), equalTo(byId.get("c").newTcf()));
    }

    /**
     * Per-boat {@code finishPosition} is authoritative for penalty assignment
     * (wiki §6.2): the boat with {@code finishPosition == 1} cops the largest
     * penalty regardless of whether some OCS-flagged boat happens to have
     * shorter raw elapsed. An OCS boat that finished physically earlier with
     * a 12th-place official result must not be assigned the 1st-place
     * penalty.
     */
    @Test
    void penaltiesFollowFinishPositionNotElapsedSort()
    {
        List<Competitor> boats = List.of(
            boat("first",  "First",  "1", 1.0),
            boat("ocs",    "OcsBoat", "2", 1.0),
            boat("third",  "Third",  "3", 1.0));
        Race race = race(60);
        LocalTime start = LocalTime.of(18, 0);
        // OCS boat sailed for 40 min (shortest raw elapsed) but finished 3rd
        // officially. The official 1st-place boat sailed for 50 min.
        Map<String, Result> results = Map.of(
            "first", new Result("first", FinishStatus.FIN, start, start.plusMinutes(50), null, 1),
            "ocs",   new Result("ocs",   FinishStatus.FIN, start, start.plusMinutes(40), null, 3),
            "third", new Result("third", FinishStatus.FIN, start, start.plusMinutes(60), null, 2));

        // Fixed scaling, so the ladder reads as the plain figures from penaltyList and
        // the test stays about which boat gets which rung.
        Map<String, Adjustment> byId = engine(JinxConfig.PenaltyScaling.FIXED, 0.0)
            .processResults(boats, race, results).stream()
            .collect(Collectors.toMap(Adjustment::boatId, a -> a));

        // penaltyList is [5,4,3,2,1]; positions 1,2,3 ⇒ 5,4,3.
        assertThat(byId.get("first").penaltyMinutes(), closeTo(5.0, TOLERANCE));
        assertThat(byId.get("third").penaltyMinutes(), closeTo(4.0, TOLERANCE));
        assertThat(byId.get("ocs").penaltyMinutes(),   closeTo(3.0, TOLERANCE));
    }


    // --- Worked example fixture (§6.5) — 7 finishers + 1 DNF, all with TCF = 1.0
    // so τᵢ is uniform and elapsed times alone drive the rankings. ---

    private static List<Competitor> workedExampleFleet()
    {
        return List.of(
            boat("p1", "Pos1", "1", 1.0),
            boat("p2", "Pos2", "2", 1.0),
            boat("p3", "Pos3", "3", 1.0),
            boat("p4", "Pos4", "4", 1.0),
            boat("p5", "Pos5", "5", 1.0),
            boat("p6", "Pos6", "6", 1.0),
            boat("p7", "Pos7", "7", 1.0),
            boat("dnf", "Dnf", "8", 1.0));
    }

    private static Map<String, Result> workedExampleResults()
    {
        LocalTime start = LocalTime.of(18, 0);
        return Map.of(
            "p1", fin("p1", start, start.plusMinutes(85)),
            "p2", fin("p2", start, start.plusMinutes(90)),
            "p3", fin("p3", start, start.plusMinutes(95)),
            "p4", fin("p4", start, start.plusMinutes(100)),
            "p5", fin("p5", start, start.plusMinutes(105)),
            "p6", fin("p6", start, start.plusMinutes(110)),
            "p7", fin("p7", start, start.plusMinutes(115)),
            "dnf", new Result("dnf", FinishStatus.DNF, start, null, null));
    }

    private static Result fin(String id, LocalTime start, LocalTime finish)
    {
        return new Result(id, FinishStatus.FIN, start, finish, null);
    }

    /**
     * An abandoned race changes nobody's handicap.
     *
     * <p>Abandoning is not a result and must not read as one. The boats that were ahead
     * when it was called off did not win, and the boats that were behind did not lose —
     * so no penalty is collected, there is no pool, and every TCF comes out the way it
     * went in. That falls out of {@code ABN} being one of the frozen statuses, which is
     * why it is an enum constant and not a flag the browser quietly drops on the floor.
     */
    @Test
    void anAbandonedRaceLeavesEveryHandicapExactlyWhereItWas()
    {
        List<Competitor> boats = workedExampleFleet();
        Map<String, Result> results = new LinkedHashMap<>();
        for (Competitor b : boats)
            results.put(b.boatId(), new Result(b.boatId(), FinishStatus.ABN, null, null, 0.0));

        List<Adjustment> out = engine.processResults(boats, race(90), results);

        assertThat(out, hasSize(boats.size()));
        for (Adjustment a : out)
        {
            assertThat(a.newTcf(), closeTo(a.oldTcf(), 1e-12));
            assertThat(a.penaltyMinutes(), closeTo(0.0, 1e-12));
            assertThat(a.rewardMinutes(), closeTo(0.0, 1e-12));
        }
    }
}
