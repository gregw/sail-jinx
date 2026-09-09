package org.mortbay.sailing.jinx.pursuit;

import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.mortbay.sailing.jinx.config.JinxConfig;
import org.mortbay.sailing.jinx.model.Adjustment;
import org.mortbay.sailing.jinx.model.Race;
import org.mortbay.sailing.jinx.model.Result;
import org.mortbay.sailing.jinx.model.StartTime;

/**
 * MYC Twilight pursuit handicap, version 2.
 * Full specification: {@code wiki/Jinx-Handicaps.md}.
 */
public class PursuitHandicapEngine implements HandicapEngine
{
    private final JinxConfig.Algorithm config;

    public PursuitHandicapEngine(JinxConfig.Algorithm config)
    {
        this.config = config;
    }

    @Override
    public List<StartTime> computeStartTimes(List<Competitor> boats, Race race)
    {
        if (boats == null || boats.isEmpty())
            return List.of();

        int tTarget = race.targetElapsedMinutes() != null ? race.targetElapsedMinutes() : 60;
        LocalTime tEarliest = race.earliestStart() != null
            ? race.earliestStart()
            : LocalTime.parse(config.earliestStart());

        double tcfMed = median(boats.stream().map(Competitor::tcf).toList());

        double[] tau = new double[boats.size()];
        double tauMax = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < boats.size(); i++)
        {
            tau[i] = tTarget * tcfMed / boats.get(i).tcf();
            if (tau[i] > tauMax) tauMax = tau[i];
        }

        // A gun is a whole minute (wiki §4). Both roundings here are to the NEAREST one:
        // an offset of 4.81 minutes is a five-minute offset, and an earliest start that
        // carries seconds is moved to the minute it is closest to rather than the one
        // below it. The second matters because every page prints HH:MM — an 18:00:40
        // earliest start used to put the whole fleet 40 seconds behind what the start
        // sheet said.
        LocalTime firstGun = toNearestMinute(tEarliest);

        List<StartTime> out = new ArrayList<>(boats.size());
        for (int i = 0; i < boats.size(); i++)
        {
            long minutesAfterEarliest = Math.round(tauMax - tau[i]);
            LocalTime startTime = firstGun.plusMinutes(minutesAfterEarliest);
            out.add(new StartTime(boats.get(i).boatId(), boats.get(i).tcf(), tau[i], startTime));
        }
        return out;
    }

    /**
     * Adjust the fleet's handicaps, in two passes.
     *
     * <p>A casual sailed, so its own handicap should move — but it is not in the series,
     * and it must not shift the handicaps of the boats that are. One boat turning up for
     * one night should not be able to change what the season's regulars are rated at.
     *
     * <p>So the algorithm runs twice:
     *
     * <ol>
     *   <li><b>Without the casuals.</b> This is the answer for every series entrant, and
     *       it is exactly the race their series had.</li>
     *   <li><b>With everybody.</b> This is the answer for the casuals alone, and it is
     *       the race they actually sailed.</li>
     * </ol>
     *
     * <p>The visible consequence is deliberate: when a casual wins, the top penalty is
     * awarded twice — to the casual, and to the first series boat home, which won its own
     * race. Neither is being over-charged; they are being charged in two different races.
     *
     * <p><b>The merged answer does not conserve.</b> Each pass redistributes its own pool
     * in full, so the series entrants still sum to zero — but the casuals' share comes
     * from a race the series boats were not scored on, so the totals do not add up across
     * the two. That is inherent to the requirement, not a bug: making them add up would
     * mean feeding the casual's residue back into the series fleet, which is the exact
     * thing the two passes exist to prevent. See
     * {@code conservationHoldsPerPassNotAcrossTheMergedAnswer}.
     */
    @Override
    public List<Adjustment> processResults(List<Competitor> boats, Race race,
                                           Map<String, Result> results)
    {
        if (boats == null || boats.isEmpty())
            return List.of();

        List<Competitor> seriesOnly = boats.stream().filter(Competitor::seeded).toList();
        // Nothing to separate: one pass is the whole answer, and is bit-for-bit what the
        // two-pass path would produce anyway.
        if (seriesOnly.size() == boats.size())
            return onePass(boats, race, results);

        // Pass 2 first, because its ordering is the one worth returning: every boat, in
        // the order it finished. Pass 1 then overwrites the series entrants' numbers.
        List<Competitor> everybody = boats.stream()
            .map(b -> b.seeded() ? b : new Competitor(b.boatId(), b.tcf(), true))
            .toList();
        List<Adjustment> withCasuals = onePass(everybody, race, results);

        Map<String, Adjustment> seriesAnswer = new LinkedHashMap<>();
        for (Adjustment a : onePass(seriesOnly, race, results))
            seriesAnswer.put(a.boatId(), a);

        List<Adjustment> merged = new ArrayList<>(withCasuals.size());
        for (Adjustment a : withCasuals)
        {
            Adjustment own = seriesAnswer.get(a.boatId());
            merged.add(own != null ? own : a);
        }
        return merged;
    }

    /** One run of the algorithm over whatever fleet it is handed. */
    private List<Adjustment> onePass(List<Competitor> boats, Race race,
                                     Map<String, Result> results)
    {
        // The sunset cap is not applied here. It shapes the course the RO lays before the
        // race, not the handicap maths afterwards — by this point the boats have sailed
        // whatever course they were given, and their elapsed times say so.

        // §5 — classify. Four buckets now rather than three: DNC has its own, because a
        // boat that stayed home draws a share of the pool and so is no longer frozen.
        record Entry(Competitor boat, double elapsedMinutes, Integer position,
                     Integer correctedFinishSeconds) {}
        List<Entry> finishers = new ArrayList<>();
        List<Competitor> dnf = new ArrayList<>();
        List<Competitor> dnc = new ArrayList<>();
        List<Competitor> frozen = new ArrayList<>();
        for (Competitor b : boats)
        {
            Result r = results == null ? null : results.get(b.boatId());
            if (r == null)
            {
                frozen.add(b);
                continue;
            }
            switch (r.status())
            {
                case FIN ->
                {
                    Duration d = r.elapsed();
                    if (d == null)
                        frozen.add(b);
                    else
                        finishers.add(new Entry(b, d.toMillis() / 60_000.0, r.finishPosition(),
                            r.correctedFinishSeconds()));
                }
                // Still racing when the race ended: it ran out of time, which is a
                // statement about the boat's speed, so its handicap eases.
                case DNF -> dnf.add(b);
                // Never came. It pays nothing and it did nothing, but it is still in the
                // series, and the pool is shared over the series — see givebackWeights.
                case DNC -> dnc.add(b);
                // DSQ, DNS, RET and ABN: frozen, and out of the placings, the giveback
                // and the pool alike.
                //
                // RET belongs here and not with DNF, though the two look alike on the
                // results sheet. A boat that RETIRED stopped for a reason that says
                // nothing about its rating — gear broke, someone was hurt, they had to be
                // somewhere. Easing its handicap for that would reward a bad night with a
                // better start, and a boat that retired often would ratchet its way down
                // the fleet without ever sailing a race.
                default -> frozen.add(b);
            }
        }

        // Official place when the caller supplied one, else elapsed order. A null
        // position sorts last so position-bearing finishers always come first.
        finishers.sort((a, c) -> {
            Integer ap = a.position(), cp = c.position();
            if (ap != null && cp != null) return Integer.compare(ap, cp);
            if (ap != null) return -1;
            if (cp != null) return 1;
            return Double.compare(a.elapsedMinutes(), c.elapsedMinutes());
        });

        // How far behind the first boat home each finisher crossed, in minutes — the
        // quantity γ shares by, when the club has asked for the proportional weighting.
        //
        // Taken over THIS pass's finishers, which matters: with casuals in the race the
        // algorithm runs twice, and the series pass must measure from the first series
        // boat rather than from a visitor who happened to win.
        //
        // Without a corrected finish — an out-of-date page, or a caller that predates the
        // field — elapsed stands in for it. That is right for a scratch race, where the
        // two orderings are the same, and is the best available answer for a pursuit one.
        boolean haveFinishTimes = !finishers.isEmpty()
            && finishers.stream().allMatch(f -> f.correctedFinishSeconds() != null);
        java.util.function.ToDoubleFunction<Entry> mark = haveFinishTimes
            ? f -> f.correctedFinishSeconds() / 60.0
            : Entry::elapsedMinutes;
        double firstHome = finishers.stream().mapToDouble(mark).min().orElse(0.0);

        // §6.1 — the race's EXPECTED duration, which is what a time adjustment is
        // measured against when it becomes a TCF change below.
        //
        // The estimate, deliberately, and not the median of what the fleet actually
        // sailed. The number being computed is a handicap for the NEXT race, and the next
        // race is far more likely to run close to its expected duration than to the
        // duration of the one just sailed — a night that overran because the breeze died
        // should not shrink every correction the season makes. A race that carries no
        // target falls back to the club's default, because the alternative is dividing by
        // zero in the middle of a night's results.
        double expectedDuration = race != null && race.targetElapsedMinutes() != null
            && race.targetElapsedMinutes() > 0
            ? race.targetElapsedMinutes()
            : config.defaultRaceDuration();

        // Everybody the pool is counted over: finishers in finish order, then the boats
        // that ran out of time, then the boats that never came. `gap` is minutes behind
        // the first boat home, and only means anything for a finisher. `elapsed` is what
        // a per-hour penalty is charged against: in a pursuit race the two are different
        // orderings entirely.
        record Participant(Competitor boat, Integer position, double gap, double penalty,
                           Kind kind) {}
        List<Participant> participants = new ArrayList<>();
        for (int i = 0; i < finishers.size(); i++)
        {
            Entry e = finishers.get(i);
            // The penalty ladder is drawn against rank among PARTICIPATING finishers, not
            // the official place. An unseeded boat is not in this race's handicap at all,
            // so it does not occupy a rung: if it finishes first, the first seeded boat
            // home still pays the first penalty. Adjustment keeps the official place for
            // display; only the ladder closes up.
            // Per-hour penalties are charged against THIS boat's time on the course, not
            // against the fleet. A boat out there for two hours has earned twice the
            // penalty of one out for one, and a median says nothing about either.
            double penalty = penaltyForRank(i + 1, e.elapsedMinutes());
            participants.add(new Participant(e.boat(),
                e.position() != null ? e.position() : (i + 1),
                mark.applyAsDouble(e) - firstHome, penalty,
                penalty > 0.0 ? Kind.PENALISED : Kind.FINISHER));
        }
        for (Competitor b : dnf)
            participants.add(new Participant(b, null, 0.0, 0.0, Kind.DNF));
        for (Competitor b : dnc)
            participants.add(new Participant(b, null, 0.0, 0.0, Kind.DNC));

        double pool = participants.stream().mapToDouble(Participant::penalty).sum();

        // §6.3 — the weights, and the pool shared by them.
        double[] weights = givebackWeights(
            participants.stream().map(Participant::kind).toArray(Kind[]::new),
            participants.stream().mapToDouble(Participant::gap).toArray(),
            boats.size(), dnc.size());
        double weightSum = 0.0;
        for (double w : weights)
            weightSum += w;

        // Nobody may receive: every boat that raced is on the penalty ladder, and there is
        // no DNF and nobody at home either. That is a fleet no bigger than penaltyList —
        // one boat sailing alone, or a five-boat series scored on [5,4,3,2,1].
        //
        // Nothing is charged. Keeping the pool would move the whole fleet's handicaps
        // against a fleet that is not there, and wiki §9 has always promised that a boat
        // racing alone finishes the night where it started. The club's answer to a series
        // this small is a shorter penaltyList, and the series form says so.
        boolean nobodyCanReceive = !(weightSum > 0.0);

        // §7 — net minutes back into TCF, against the race's expected duration.
        //   newTcf = tcf / (1 − net × tcf / (expectedDuration × tcfMed))
        // No fleet-wide anchor correction: the next race's start-time pass over the
        // updated TCFs is what brings the new slowest boat back to t_earliest.
        double tcfMed = median(participants.stream()
            .map(p -> p.boat().tcf()).toList());
        double scale = expectedDuration * tcfMed;
        if (!(scale > 0.0))
        {
            throw new IllegalStateException(
                "handicap scale must be positive, but expectedDuration=" + expectedDuration
                    + " × medianTcf=" + tcfMed + " = " + scale
                    + " — cannot convert time adjustments into TCF changes");
        }

        List<Adjustment> adjustments = new ArrayList<>(boats.size());
        for (int i = 0; i < participants.size(); i++)
        {
            Participant p = participants.get(i);
            double penalty = nobodyCanReceive ? 0.0 : p.penalty();
            double reward = nobodyCanReceive ? 0.0 : pool * weights[i] / weightSum;
            double net = penalty - reward;
            double oldTcf = p.boat().tcf();
            double denom = 1.0 - net * oldTcf / scale;
            if (!(denom > 0.0))
            {
                throw new IllegalStateException(
                    "TCF conversion denominator must be positive for " + p.boat().boatId()
                        + " but was " + denom + " (net=" + net + ", tcf=" + oldTcf
                        + ", expectedDuration=" + expectedDuration + ", medianTcf=" + tcfMed
                        + ") — a penalty this large against a race this short cannot be "
                        + "expressed as a handicap change");
            }
            adjustments.add(new Adjustment(p.boat().boatId(), p.position(),
                penalty, reward, net, oldTcf, oldTcf / denom));
        }
        // Frozen boats — RET, DSQ, DNS, ABN — still get a row, with zero deltas and their
        // TCF untouched, so the audit and the table show them.
        for (Competitor b : frozen)
            adjustments.add(new Adjustment(b.boatId(), null, 0.0, 0.0, 0.0, b.tcf(), b.tcf()));

        return adjustments;
    }

    /** What a boat did, as far as the giveback is concerned. */
    private enum Kind { PENALISED, FINISHER, DNF, DNC }

    /**
     * The penalty for finishing in this position, in minutes.
     *
     * <p>Under {@code perHour} the listed figure is a rate, charged against
     * {@code boatElapsedMinutes} — the penalised boat's own time on the course. Against
     * its own, not the fleet's: a boat out there for two hours has earned twice the
     * penalty of one out for one, and the fleet's median said nothing about either of
     * them. It used to be the median, which made the penalty a statement about the night
     * rather than about the boat.
     */
    private double penaltyForRank(int rank, double boatElapsedMinutes)
    {
        int idx = rank - 1;
        if (idx < 0 || idx >= config.penaltyList().size())
            return 0.0;
        double listed = config.penaltyList().get(idx);
        return config.penaltyScaling() == JinxConfig.PenaltyScaling.PER_HOUR
            ? listed * Math.max(0.0, boatElapsedMinutes) / 60.0
            : listed;
    }

    /**
     * How the penalty pool is shared, as a weight per participant.
     *
     * <p>The unit is <b>one ordinary finisher</b> — a boat that got round and was not on
     * the penalty ladder. Every other weight is a multiple of that, which is what lets
     * the club read them off against each other:
     *
     * <pre>
     *   a boat in a penalty place   0
     *   any other finisher          1 + γ·gap/maxGap      (just 1 at the default γ = 0)
     *   a boat that ran out of time the last finisher's weight + (dnfWeight − 1)
     *   a boat that never came      dncWeight × d/N
     * </pre>
     *
     * <p><b>The pool comes back to the entry list, not to the boats that raced.</b> That
     * is the whole change. Sharing it among the starters divided a fixed pool by a
     * varying fleet, so the giveback went as 1/turnout: thirty boats out and the pool was
     * spread thirty ways, five boats out and the same fifteen minutes came straight back
     * to the five that had just been charged it. The boat that finished last of five
     * collected six minutes for the privilege — a better handicap outcome than winning.
     *
     * <p><b>Why a penalty place draws nothing.</b> The ladder is the club's statement of
     * what a good result costs; giving part of it straight back in the same breath makes
     * the printed 5, 4, 3, 2, 1 a fiction. It also replaces what γ = 1 used to do for the
     * winner alone, and does it for every penalised place rather than only the one whose
     * gap happened to be zero.
     *
     * <p><b>Why a non-starter's weight rises as the fleet empties.</b> {@code d/N} is the
     * share of the entry list that stayed home. On a full night the two or three absentees
     * are worth almost nothing and the pool circulates among the boats that raced, which
     * is right — they are the race. On a thin night most of the fleet is at home and most
     * of the pool goes there, which is also right: five boats out of thirty all had a good
     * night by default, and the handicap should say so relative to the fleet they did not
     * have to beat. The product is bounded by one, so <b>absence is never worth more than
     * racing</b>.
     *
     * <p>What it is NOT is a reward for staying home in any absolute sense. Nothing is
     * created: the fleet's adjustments still sum to zero, so a boat that never comes is
     * only ever moving relative to boats that did.
     *
     * <p><b>γ gives an extra boat for how far behind a boat was.</b> A finisher is worth
     * one boat for getting round and up to one more for how far behind the first boat home
     * it crossed. {@code dncWeight} does not move with it, so turning the dial up moves the
     * pool away from the boats that stayed home and towards the boats that raced.
     *
     * <p>These weights are deliberately <b>not</b> normalised. An earlier version divided
     * the finishers through by their own mean, so that an ordinary finisher stayed worth
     * exactly one boat at every γ. That is tidier and it is not what the knob is for: it
     * left the finishers holding the same total weight as at γ = 0, so the dial only ever
     * reshuffled the pool among them and never shifted any of it their way. It survived a
     * full suite because every γ test compared two finishers with each other, and a ratio
     * cannot see a factor common to both.
     *
     * <p><b>A retirement is the last boat home plus a fifth of a boat</b> — {@code lastHome
     * + (dnfWeight − 1)}, so at the default 1.2 it sits a constant 0.2 above whatever the
     * furthest-behind finisher is worth, at every γ. That keeps §6.3.1 true at any
     * setting <em>and</em> lets γ's bonus reach the finishers.
     *
     * <p>The two obvious alternatives each break one of those, which is why this one is
     * neither:
     *
     * <ul>
     *   <li><b>Flat {@code dnfWeight}.</b> γ adds up to a whole boat to a finisher and a
     *       flat 1.2 does not move, so at about γ = 0.2 the boats furthest behind overtake
     *       the DNF and a retirement stops being the largest single share.</li>
     *   <li><b>{@code dnfWeight × lastHome}.</b> The DNF then takes γ's bonus in proportion
     *       too, which cancels the shift: the finishers' total goes flat across the dial
     *       and γ only moves the pool off the boats at home. Measured, not guessed —
     *       13.235 at γ = 0 against 13.232 at γ = 1 on the twenty-of-thirty fixture.</li>
     * </ul>
     *
     * <p>All three are the same number at the club's γ = 0.
     */
    private double[] givebackWeights(Kind[] kinds, double[] gaps, int entered, int stayedHome)
    {
        int n = kinds.length;
        double[] out = new double[n];
        if (n == 0)
            return out;

        double gamma = config.givebackGamma();

        // The eligible finishers set the scale. maxGap over those, not over the whole
        // fleet: the penalised boats are the ones nearest the front, so including them
        // would shrink every eligible weight towards the top of the range for no reason.
        double maxGap = 0.0;
        for (int i = 0; i < n; i++)
        {
            if (kinds[i] == Kind.FINISHER)
                maxGap = Math.max(maxGap, Math.max(0.0, gaps[i]));
        }

        // What the last boat home is worth. A retirement is scored at that PLUS
        // (dnfWeight − 1), so it outdraws every boat that got round by a constant margin
        // at every γ — and γ's bonus still reaches the finishers, which it would not if
        // dnfWeight multiplied this instead. See givebackWeights' javadoc.
        double lastHome = maxGap > 0.0 ? 1.0 + gamma : 1.0;

        for (int i = 0; i < n; i++)
        {
            out[i] = switch (kinds[i])
            {
                case PENALISED -> 0.0;
                // One boat for getting round, and up to one more for how far behind the
                // first boat home it crossed. Every eligible boat on the same gap — a
                // dead heat, or a single eligible finisher — makes the ratio meaningless,
                // and they draw alike. That is the answer rather than a fallback from an
                // error: boats that cannot be separated should not be separated.
                case FINISHER -> maxGap > 0.0
                    ? 1.0 + gamma * Math.max(0.0, gaps[i]) / maxGap
                    : 1.0;
                case DNF -> lastHome + (config.dnfWeight() - 1.0);
                case DNC -> entered > 0
                    ? config.dncWeight() * (double)stayedHome / entered
                    : 0.0;
            };
        }
        return out;
    }

    /** The whole minute this time is closest to, rounding a half-minute up. */
    static LocalTime toNearestMinute(LocalTime t)
    {
        LocalTime onTheMinute = t.truncatedTo(ChronoUnit.MINUTES);
        return t.getSecond() >= 30 ? onTheMinute.plusMinutes(1) : onTheMinute;
    }

    private static double median(List<Double> values)
    {
        if (values.isEmpty()) return 1.0;
        List<Double> sorted = new ArrayList<>(values);
        sorted.sort(Comparator.naturalOrder());
        int n = sorted.size();
        if ((n & 1) == 1) return sorted.get(n / 2);
        return (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2.0;
    }
}
