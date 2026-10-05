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
 * The rules that do not depend on how the pool comes back: who is in the arithmetic at
 * all (casuals, retirements, boats at home), what a penalty is measured against, and a
 * ladder too long for the fleet. How the minutes come back is {@link MinuteGivebackTest}.
 */
class HandicapRulesTest
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

    private static JinxConfig.Algorithm alg()
    {
        return new JinxConfig.Algorithm(PENALTIES, 90, "18:00", -33.8, 151.2833, false);
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
    void aPlaceBeyondTheListCostsNothing()
    {
        List<Sailed> big = new ArrayList<>(fleet());
        big.add(new Sailed("f", 1.0, 130));
        big.add(new Sailed("g", 1.0, 140));
        Map<String, Adjustment> out = byId(new PursuitHandicapEngine(alg())
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

        PursuitHandicapEngine engine = new PursuitHandicapEngine(alg());
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

        Map<String, Adjustment> out = byId(new PursuitHandicapEngine(alg())
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
        // A series boat at home, so neither pass has a leftover to discard.
        boats.add(new Competitor("away", 1.0));
        Map<String, Result> results = new LinkedHashMap<>(resultsOf(withCasual, 1.0));
        results.put("away", new Result("away", FinishStatus.DNC, null, null, null));

        List<Adjustment> out = new PursuitHandicapEngine(alg())
            .processResults(boats, race(), results);

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

        List<Adjustment> out = new PursuitHandicapEngine(alg())
            .processResults(boats, race(), resultsOf(withCasuals, 1.0));

        assertThat(out.size(), equalTo(7));
        assertThat(out.stream().map(Adjustment::boatId).distinct().count(), equalTo(7L));
        // A casual that finished last still draws from the pool of the race it was in.
        assertThat(out.stream().filter(a -> a.boatId().equals("c2"))
            .findFirst().orElseThrow().rewardMinutes() > 0.0, is(true));
    }

    /**
     * A retirement is scored exactly as a boat that did not start.
     *
     * <p>DNF and RET are not the same thing and must not be scored the same way. A boat
     * that is <b>DNF</b> was still racing when the race ended — it ran out of time, which
     * is a statement about its speed, so it is served first. A boat that <b>retired</b>
     * stopped for a reason that has nothing to do with its rating: gear broke, someone was
     * hurt, they had to be somewhere. It was there, so like a DNS it may take up to a
     * minute of what the racers and the duty boat leave — but no more, and no sooner.
     */
    @Test
    void aRetirementIsScoredAsANonStarter()
    {
        List<Sailing> raced = pursuitFleet();
        List<Competitor> boats = new ArrayList<>(raced.stream()
            .map(s -> new Competitor(s.id(), s.tcf())).toList());
        boats.add(new Competitor("gear", 1.0));

        Map<String, Map<String, Adjustment>> by = new LinkedHashMap<>();
        for (FinishStatus status : List.of(FinishStatus.RET, FinishStatus.DNS))
        {
            Map<String, Result> results = new LinkedHashMap<>(pursuit(raced));
            results.put("gear", new Result("gear", status, null, null, null));
            by.put(status.name(), byId(new PursuitHandicapEngine(alg())
                .processResults(boats, race(), results)));
        }

        Adjustment ret = by.get("RET").get("gear");
        assertThat(ret.penaltyMinutes(), closeTo(0.0, 1e-12));
        assertThat(ret.rewardMinutes(), closeTo(1.0, 1e-12));
        assertThat(ret.finishPosition(), is((Integer)null));
        for (Competitor b : boats)
        {
            assertThat("RET and DNS must agree on " + b.boatId(),
                by.get("RET").get(b.boatId()).newTcf(),
                closeTo(by.get("DNS").get(b.boatId()).newTcf(), 1e-12));
        }
    }

    /**
     * A DNF is not frozen — it ran out of time, and that is about its speed.
     *
     * <p>The pair with {@link #aRetirementIsScoredAsANonStarter}:
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

        Adjustment dnf = byId(new PursuitHandicapEngine(alg())
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
     * <p>So a DNC takes what the boats that raced cannot: once every one of them has its
     * minute, the rest comes home. It still pays nothing and it still has no place; what
     * it no longer does is stand outside the arithmetic while the boats that turned up
     * hand the pool back to each other.
     */
    @Test
    void aDncBoatTakesWhatTheRacersCannotWithoutChangingAnybodysPenalty()
    {
        List<Competitor> boats = new ArrayList<>(competitors(fleet()));
        boats.add(new Competitor("ghost", 1.0));
        Map<String, Result> results = new LinkedHashMap<>(resultsOf(fleet(), 1.0));
        results.put("ghost", new Result("ghost", FinishStatus.DNC, null, null, null));

        Map<String, Adjustment> out = byId(new PursuitHandicapEngine(alg())
            .processResults(boats, race(), results));

        // Nine minutes charged, three boats behind the ladder: a minute each, and the
        // six nobody out there could take go home.
        for (String id : List.of("c", "d", "e"))
            assertThat(id, out.get(id).rewardMinutes(), closeTo(1.0, 1e-9));
        assertThat(out.get("ghost").rewardMinutes(), closeTo(6.0, 1e-9));
        assertThat("its handicap eases",
            out.get("ghost").newTcf() < out.get("ghost").oldTcf(), is(true));
        assertThat(out.get("ghost").penaltyMinutes(), closeTo(0.0, 1e-12));
        assertThat(out.get("ghost").finishPosition(), is((Integer) null));

        // A boat that never came still cannot change what anybody else is CHARGED.
        assertThat(out.get("a").penaltyMinutes(), closeTo(5.0, 1e-9));
    }

    @Test
    void retirementsDrawFromThePoolWithoutChangingAnybodysPenalty()
    {
        List<Competitor> boats = new ArrayList<>(competitors(fleet()));
        boats.add(new Competitor("quit1", 1.0));
        boats.add(new Competitor("quit2", 1.0));
        boats.add(new Competitor("quit3", 1.0));
        Map<String, Result> results = new LinkedHashMap<>(resultsOf(fleet(), 1.0));
        // DNF, not RET: a retirement waits behind the racers and the duty boat.
        for (String id : List.of("quit1", "quit2", "quit3"))
            results.put(id, new Result(id, FinishStatus.DNF, null, null, null));

        // Three boats running out of time does not make the winner's penalty larger.
        // It used to be able to, when the penalty was a rate against the fleet's median
        // elapsed and a retirement could join that sample.
        Map<String, Adjustment> off = byId(new PursuitHandicapEngine(alg())
            .processResults(boats, race(), results));
        assertThat(off.get("a").penaltyMinutes(), closeTo(5.0, 1e-9));

        // Boats that ran out of time still draw from the pool — they sailed. Nine
        // minutes, six receivers, nobody at home: a minute each, and three discarded.
        for (String id : List.of("quit1", "quit2", "quit3"))
        {
            assertThat(id, off.get(id).rewardMinutes(), closeTo(1.0, 1e-9));
            assertThat(id, off.get(id).penaltyMinutes(), closeTo(0.0, 1e-12));
        }
        for (String id : List.of("c", "d", "e"))
            assertThat(id, off.get(id).rewardMinutes(), closeTo(1.0, 1e-9));
    }

    @Test
    void aRaceWithNobodyHomeChangesNoHandicaps()
    {
        List<Competitor> boats = competitors(fleet());
        Map<String, Result> none = new LinkedHashMap<>();
        for (Competitor b : boats)
            none.put(b.boatId(), new Result(b.boatId(), FinishStatus.DNC, null, null, null));

        List<Adjustment> out = new PursuitHandicapEngine(alg())
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
        PursuitHandicapEngine engine = new PursuitHandicapEngine(alg());

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
        PursuitHandicapEngine engine = new PursuitHandicapEngine(alg());

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

    // --- a ladder too long for the fleet ---------------------------------------

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
     * none of them may receive, and there is nobody at home to catch the pool either. Keeping it would move the whole fleet's handicaps against a fleet that is
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
            List.of(5.0, 4.0, 3.0, 2.0, 1.0), 90, "18:00", -33.8, 151.2833, false);
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
     * the three behind it take a minute each. Nobody is at home, so the other two are
     * discarded.
     *
     * <p>This is the pair to the test above — the fix for a small fleet is the length of
     * the list, not anything in the arithmetic.
     */
    @Test
    void aLadderThatFitsTheFleetBehavesNormally()
    {
        JinxConfig.Algorithm shortLadder = new JinxConfig.Algorithm(List.of(5.0), 90,
            "18:00", -33.8, 151.2833, false);
        Map<String, Adjustment> out = byId(new PursuitHandicapEngine(shortLadder)
            .processResults(fourBoats(), race(), spreadOfFour()));

        assertThat(out.get("first").penaltyMinutes(), closeTo(5.0, 1e-9));
        assertThat(out.get("first").rewardMinutes(), closeTo(0.0, 1e-9));
        for (String id : List.of("second", "third", "last"))
            assertThat(id, out.get(id).rewardMinutes(), closeTo(1.0, 1e-9));
        assertThat(out.values().stream().mapToDouble(Adjustment::netAdjustmentMinutes).sum(),
            closeTo(2.0, 1e-9));
    }
}
