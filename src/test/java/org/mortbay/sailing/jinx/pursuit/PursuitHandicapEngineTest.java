package org.mortbay.sailing.jinx.pursuit;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.mortbay.sailing.jinx.config.JinxConfig;
import org.mortbay.sailing.jinx.model.Adjustment;
import org.mortbay.sailing.jinx.model.Boat;
import org.mortbay.sailing.jinx.model.Calibration;
import org.mortbay.sailing.jinx.model.FinishStatus;
import org.mortbay.sailing.jinx.model.Race;
import org.mortbay.sailing.jinx.model.RaceStatus;
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
        List.of(5, 4, 3, 2, 1), 90, 5, "18:00", -33.8000, 151.2833, false);

    private static final double TOLERANCE = 0.01;

    private final PursuitHandicapEngine engine = new PursuitHandicapEngine(DEFAULT_ALG);

    /**
     * Spec §4: the slowest boat (lowest TCF) starts at {@code t_earliest_start};
     * the fastest boat starts last. Start times are rounded to the nearest minute.
     */
    @Test
    void slowestBoatStartsFirstAtEarliestStart()
    {
        List<Boat> boats = List.of(
            new Boat("slow", "Slow Turtle",   "AUS1",   "d", "Div", 0.8821),
            new Boat("mid",  "Meridian",      "MYC12",  "d", "Div", 0.9340),
            new Boat("fast", "Flashpoint",    "AUS5",   "d", "Div", 1.0450));
        Race race = new Race("r1", 1, "R1", LocalDate.of(2026, 5, 1),
            60, LocalTime.of(18, 0), RaceStatus.SCHEDULED);

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
        List<Boat> boats = workedExampleFleet();
        Race race = new Race("r1", 1, "R1", LocalDate.of(2026, 5, 1),
            90, LocalTime.of(18, 0), RaceStatus.RESULTS_ENTERED);
        Map<String, Result> results = workedExampleResults();

        List<Adjustment> adjustments = engine.processResults(boats, race, results);

        double sum = adjustments.stream()
            .mapToDouble(Adjustment::netAdjustmentMinutes)
            .sum();
        assertThat(sum, closeTo(0.0, TOLERANCE));
    }

    /**
     * Spec §6.5 worked example. With t_target_elapsed = 90 and idealRaceLength = 90,
     * γ = 0.5. The 1st-place finisher should receive +5 min penalty, ~1.71 reward,
     * giving a net of approximately +3.29 minutes.
     */
    @Test
    void firstPlaceAdjustmentMatchesWorkedExample()
    {
        List<Boat> boats = workedExampleFleet();
        Race race = new Race("r1", 1, "R1", LocalDate.of(2026, 5, 1),
            90, LocalTime.of(18, 0), RaceStatus.RESULTS_ENTERED);
        Map<String, Result> results = workedExampleResults();

        Adjustment first = engine.processResults(boats, race, results).stream()
            .filter(a -> a.finishPosition() != null && a.finishPosition() == 1)
            .findFirst().orElseThrow();

        assertThat(first.penaltyMinutes(), closeTo(5.0, TOLERANCE));
        assertThat(first.rewardMinutes(), closeTo(1.71, 0.05));
        assertThat(first.netAdjustmentMinutes(), closeTo(3.29, 0.05));
    }

    /**
     * Spec §5: DSQ boats are excluded from adjustments — their TCF is frozen.
     */
    @Test
    void dsqBoatTcfIsFrozen()
    {
        List<Boat> boats = List.of(
            new Boat("a", "A", "1", "d", "Div", 1.0),
            new Boat("b", "B", "2", "d", "Div", 1.0));
        Race race = new Race("r1", 1, "R1", LocalDate.of(2026, 5, 1),
            60, LocalTime.of(18, 0), RaceStatus.RESULTS_ENTERED);
        Map<String, Result> results = Map.of(
            "a", new Result("a", FinishStatus.FIN, LocalTime.of(18, 0), LocalTime.of(19, 0), null),
            "b", new Result("b", FinishStatus.DSQ, LocalTime.of(18, 0), null, null));

        Adjustment b = engine.processResults(boats, race, results).stream()
            .filter(a -> a.boatId().equals("b")).findFirst().orElseThrow();

        assertThat(b.oldTcf(), equalTo(b.newTcf()));
        assertThat(b.netAdjustmentMinutes(), closeTo(0.0, TOLERANCE));
    }

    /**
     * Spec §5: DNF/RET boats share an effective elapsed time of the slowest
     * finisher + dnfAllowance, so they receive identical rewards.
     */
    @Test
    void dnfBoatsShareEqualReward()
    {
        List<Boat> boats = List.of(
            new Boat("p1", "Pos1", "1", "d", "Div", 1.0),
            new Boat("p2", "Pos2", "2", "d", "Div", 1.0),
            new Boat("dnfA", "DnfA", "3", "d", "Div", 1.0),
            new Boat("dnfB", "DnfB", "4", "d", "Div", 1.0));
        Race race = new Race("r1", 1, "R1", LocalDate.of(2026, 5, 1),
            90, LocalTime.of(18, 0), RaceStatus.RESULTS_ENTERED);
        LocalTime start = LocalTime.of(18, 0);
        Map<String, Result> results = Map.of(
            "p1", fin("p1", start, start.plusMinutes(60)),
            "p2", fin("p2", start, start.plusMinutes(80)),
            "dnfA", new Result("dnfA", FinishStatus.DNF, start, null, null),
            "dnfB", new Result("dnfB", FinishStatus.RET, start, null, null));

        Map<String, Adjustment> byId = engine.processResults(boats, race, results).stream()
            .collect(Collectors.toMap(Adjustment::boatId, a -> a));

        // Both DNF/RET boats see the same elapsed-time treatment.
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
        List<Boat> boats = workedExampleFleet();
        Race race = new Race("r1", 1, "R1", LocalDate.of(2026, 5, 1),
            90, LocalTime.of(18, 0), RaceStatus.RESULTS_ENTERED);
        Map<String, Result> results = workedExampleResults();

        Map<String, Adjustment> byId = engine.processResults(boats, race, results).stream()
            .collect(Collectors.toMap(Adjustment::boatId, a -> a));

        // 1st place: penalty 5, reward ~1.71, net +3.29 → TCF goes up.
        assertThat(byId.get("p1").newTcf() > byId.get("p1").oldTcf(), equalTo(true));
        // DNF: penalty 0, reward ~2.03, net −2.03 → TCF goes down.
        assertThat(byId.get("dnf").newTcf() < byId.get("dnf").oldTcf(), equalTo(true));
    }

    /**
     * Calibration mode: newTcf is derived from V₀ and the race's actual course
     * distance (median over finishers of {@code TCF × V₀ × elapsed/60}), not
     * from the fleet-median TCF.
     *
     * <p>Worked example fleet: 7 finishers @ 85,90,…,115 min + 1 DNF, all
     * TCF=1.0. With V₀ = 6.0 kn, median finish = 100 min ⇒ D_race = 10.0 nm.
     * For boat p1 with net Δs ≈ +3.29 min:
     * <pre>
     *   newTcf = 1.0 / (1 − 1.0 × 3.29 × 6.0 / (60 × 10.0))
     *          = 1.0 / (1 − 0.0329) ≈ 1.0340
     * </pre>
     * The {@code tcfMed}-based fallback formula would give ~1.0379, so this
     * value is uniquely identifying the V₀-based path.
     */
    @Test
    void newTcfUsesCalibrationVZeroWhenProvided()
    {
        Calibration cal = new Calibration(
            6.0, 100.0, 0.79, 0L, 1.12, 22320L,
            Instant.parse("2026-05-29T09:30:00Z"));
        PursuitHandicapEngine calEngine = new PursuitHandicapEngine(DEFAULT_ALG, cal);

        List<Boat> boats = workedExampleFleet();
        Race race = new Race("r1", 1, "R1", LocalDate.of(2026, 5, 1),
            90, LocalTime.of(18, 0), RaceStatus.RESULTS_ENTERED);
        Map<String, Result> results = workedExampleResults();

        Adjustment p1 = calEngine.processResults(boats, race, results).stream()
            .filter(a -> a.finishPosition() != null && a.finishPosition() == 1)
            .findFirst().orElseThrow();

        assertThat(p1.newTcf(), closeTo(1.0340, 0.0005));
    }

    /**
     * Direction-and-magnitude check across the V₀-based formula: positive Δs
     * raises TCF, negative lowers it, and DSQ boats stay frozen.
     */
    @Test
    void calibrationModeKeepsDsqFrozenAndPreservesDirection()
    {
        Calibration cal = new Calibration(
            6.0, 100.0, 0.79, 0L, 1.12, 22320L,
            Instant.parse("2026-05-29T09:30:00Z"));
        PursuitHandicapEngine calEngine = new PursuitHandicapEngine(DEFAULT_ALG, cal);

        List<Boat> boats = List.of(
            new Boat("a", "A", "1", "d", "Div", 1.0),
            new Boat("b", "B", "2", "d", "Div", 1.0),
            new Boat("c", "C", "3", "d", "Div", 1.0));
        Race race = new Race("r1", 1, "R1", LocalDate.of(2026, 5, 1),
            60, LocalTime.of(18, 0), RaceStatus.RESULTS_ENTERED);
        LocalTime start = LocalTime.of(18, 0);
        Map<String, Result> results = Map.of(
            "a", fin("a", start, start.plusMinutes(55)),
            "b", fin("b", start, start.plusMinutes(65)),
            "c", new Result("c", FinishStatus.DSQ, start, null, null));

        Map<String, Adjustment> byId = calEngine.processResults(boats, race, results).stream()
            .collect(Collectors.toMap(Adjustment::boatId, a -> a));

        assertThat(byId.get("a").newTcf() > byId.get("a").oldTcf(), equalTo(true));
        assertThat(byId.get("b").newTcf() < byId.get("b").oldTcf(), equalTo(true));
        assertThat(byId.get("c").oldTcf(), equalTo(byId.get("c").newTcf()));
    }

    // --- Worked example fixture (§6.5) — 7 finishers + 1 DNF, all with TCF = 1.0
    // so τᵢ is uniform and elapsed times alone drive the rankings. ---

    private static List<Boat> workedExampleFleet()
    {
        return List.of(
            new Boat("p1", "Pos1", "1", "d", "Div", 1.0),
            new Boat("p2", "Pos2", "2", "d", "Div", 1.0),
            new Boat("p3", "Pos3", "3", "d", "Div", 1.0),
            new Boat("p4", "Pos4", "4", "d", "Div", 1.0),
            new Boat("p5", "Pos5", "5", "d", "Div", 1.0),
            new Boat("p6", "Pos6", "6", "d", "Div", 1.0),
            new Boat("p7", "Pos7", "7", "d", "Div", 1.0),
            new Boat("dnf", "Dnf", "8", "d", "Div", 1.0));
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
}
