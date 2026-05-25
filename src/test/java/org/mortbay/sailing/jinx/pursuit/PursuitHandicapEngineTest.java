package org.mortbay.sailing.jinx.pursuit;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.mortbay.sailing.jinx.config.JinxConfig;
import org.mortbay.sailing.jinx.model.Adjustment;
import org.mortbay.sailing.jinx.model.Boat;
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
 * Test-first specification for the pursuit handicap algorithm
 * ({@code wiki/myc-twilight-handicap-v2.md}).
 *
 * <p>These tests are deliberately RED today — {@link PursuitHandicapEngine} is a
 * skeleton with {@code UnsupportedOperationException} bodies. The tests are
 * the executable spec the implementation has to satisfy. Each test maps to a
 * specific section of the algorithm document so the eventual implementer can
 * tick them off one by one.
 */
class PursuitHandicapEngineTest
{
    private static final JinxConfig.Algorithm DEFAULT_ALG = new JinxConfig.Algorithm(
        List.of(5, 4, 3, 2, 1), 90, 5, "18:00");

    private static final double TOLERANCE = 0.01;

    private final PursuitHandicapEngine engine = new PursuitHandicapEngine(DEFAULT_ALG);

    /**
     * Spec §4: the slowest boat (lowest TCF) starts at {@code t_earliest_start};
     * the fastest boat starts last. Start times are rounded to the nearest minute.
     */
    @Test
    // TODO: implement PursuitHandicapEngine.computeStartTimes per wiki §4
    //   (τᵢ = t_target × TCF_med / TCFᵢ; Δtᵢ = τ_max - τᵢ; round to minute) then remove @Disabled.
    @Disabled("Pending PursuitHandicapEngine.computeStartTimes implementation")
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
    // TODO: implement PursuitHandicapEngine.processResults so the penalty pool is fully
    //   redistributed (wiki §6.4 — Σ Δsᵢ = 0 across participating boats) then remove @Disabled.
    @Disabled("Pending PursuitHandicapEngine.processResults implementation")
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
    // TODO: implement processResults to match the wiki §6.5 worked example
    //   (γ = 0.5, 1st place: +5 penalty, ~1.71 reward, +3.29 net) then remove @Disabled.
    @Disabled("Pending PursuitHandicapEngine.processResults implementation")
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
    // TODO: implement processResults so DSQ / DNC / DNS boats are excluded from
    //   adjustments and their TCF is left unchanged (wiki §5) then remove @Disabled.
    @Disabled("Pending PursuitHandicapEngine.processResults implementation")
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
