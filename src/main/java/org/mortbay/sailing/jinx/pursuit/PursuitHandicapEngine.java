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
     * in full (unless nobody is at home to take a leftover — see minuteGiveback), so the
     * series entrants still sum to zero — but the casuals' share comes
     * from a race the series boats were not scored on, so the totals do not add up across
     * the two. That is inherent to the requirement, not a bug: making them add up would
     * mean feeding the casual's residue back into the series fleet, which is the exact
     * thing the two passes exist to prevent. See
     * {@code conservationHoldsPerPassNotAcrossTheMergedAnswer}.
     */
    @Override
    public List<Adjustment> processResults(List<Competitor> boats, Race race,
                                           Map<String, Result> results,
                                           Integer nextRaceMinutes)
    {
        if (boats == null || boats.isEmpty())
            return List.of();

        List<Competitor> seriesOnly = boats.stream().filter(Competitor::seeded).toList();
        // Nothing to separate: one pass is the whole answer, and is bit-for-bit what the
        // two-pass path would produce anyway.
        if (seriesOnly.size() == boats.size())
            return onePass(boats, race, results, nextRaceMinutes);

        // Pass 2 first, because its ordering is the one worth returning: every boat, in
        // the order it finished. Pass 1 then overwrites the series entrants' numbers.
        List<Competitor> everybody = boats.stream()
            .map(b -> b.seeded() ? b : new Competitor(b.boatId(), b.tcf(), true))
            .toList();
        List<Adjustment> withCasuals = onePass(everybody, race, results, nextRaceMinutes);

        Map<String, Adjustment> seriesAnswer = new LinkedHashMap<>();
        for (Adjustment a : onePass(seriesOnly, race, results, nextRaceMinutes))
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
                                     Map<String, Result> results, Integer nextRaceMinutes)
    {
        // The sunset cap is not applied here. It shapes the course the RO lays before the
        // race, not the handicap maths afterwards — by this point the boats have sailed
        // whatever course they were given, and their elapsed times say so.

        // §5 — classify. Four buckets now rather than three: DNC has its own, because a
        // boat that stayed home draws a share of the pool and so is no longer frozen.
        record Entry(Competitor boat, double elapsedMinutes, Integer position) {}
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
                        finishers.add(new Entry(b, d.toMillis() / 60_000.0, r.finishPosition()));
                }
                // Still racing when the race ended: it ran out of time, which is a
                // statement about the boat's speed, so its handicap eases.
                case DNF -> dnf.add(b);
                // Never came. It pays nothing and it did nothing, but it is still in the
                // series, and takes what the boats that raced could not — see
                // minuteGiveback.
                case DNC -> dnc.add(b);
                // DSQ, DNS, RET and ABN: frozen, and out of the placings and the
                // giveback alike.
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

        // §6.1 — the NEXT race's expected duration, which is what a time adjustment is
        // measured against when it becomes a TCF change below.
        //
        // The next race's, because that is where the new TCF is sailed: a start time is
        // τ = T·med/tcf, so the conversion moves a boat's start by exactly its net
        // minutes only when T is the T of the race it is used in. Measured against this
        // race instead, a five-minute penalty earned on a 90-minute night would be three
        // and a third minutes on a 60-minute one.
        //
        // The estimate, deliberately, and not what the fleet actually sailed — a night
        // that overran because the breeze died should not shrink every correction the
        // season makes. The last race of a series has no next one and falls back to its
        // own target, then to the club's default, because the alternative is dividing by
        // zero in the middle of a night's results.
        double expectedDuration;
        if (nextRaceMinutes != null && nextRaceMinutes > 0)
            expectedDuration = nextRaceMinutes;
        else if (race != null && race.targetElapsedMinutes() != null
            && race.targetElapsedMinutes() > 0)
            expectedDuration = race.targetElapsedMinutes();
        else
            expectedDuration = config.defaultRaceDuration();

        // Everybody the pool is counted over: finishers in finish order, then the boats
        // that ran out of time, then the boats that never came.
        record Participant(Competitor boat, Integer position, double penalty, Kind kind) {}
        List<Participant> participants = new ArrayList<>();
        for (int i = 0; i < finishers.size(); i++)
        {
            Entry e = finishers.get(i);
            // The penalty ladder is drawn against rank among PARTICIPATING finishers, not
            // the official place. An unseeded boat is not in this race's handicap at all,
            // so it does not occupy a rung: if it finishes first, the first seeded boat
            // home still pays the first penalty. Adjustment keeps the official place for
            // display; only the ladder closes up.
            double penalty = penaltyForRank(i + 1);
            participants.add(new Participant(e.boat(),
                e.position() != null ? e.position() : (i + 1), penalty,
                penalty > 0.0 ? Kind.PENALISED : Kind.FINISHER));
        }
        for (Competitor b : dnf)
            participants.add(new Participant(b, null, 0.0, Kind.DNF));
        for (Competitor b : dnc)
            participants.add(new Participant(b, null, 0.0, Kind.DNC));

        double pool = participants.stream().mapToDouble(Participant::penalty).sum();

        // §6.3 — the pool, handed back a minute at a time.
        double[] rewards = minuteGiveback(
            participants.stream().map(Participant::kind).toArray(Kind[]::new), pool);

        // Nobody may receive: every boat that raced is on the penalty ladder, and there is
        // no DNF and nobody at home either. That is a fleet no bigger than penaltyList —
        // one boat sailing alone, or a five-boat series scored on [5,4,3,2,1].
        //
        // Nothing is charged. Keeping the pool would move the whole fleet's handicaps
        // against a fleet that is not there, and wiki §9 has always promised that a boat
        // racing alone finishes the night where it started. The club's answer to a series
        // this small is a shorter penaltyList, and the series form says so.
        boolean nobodyCanReceive = participants.stream()
            .noneMatch(p -> p.kind() != Kind.PENALISED);

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
            double reward = nobodyCanReceive ? 0.0 : rewards[i];
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

    /** The penalty for finishing in this position, in whole minutes. */
    private double penaltyForRank(int rank)
    {
        int idx = rank - 1;
        if (idx < 0 || idx >= config.penaltyList().size())
            return 0.0;
        return config.penaltyList().get(idx);
    }

    /**
     * How the penalty pool comes back, as minutes per participant.
     *
     * <p>A minute at a time, in this order, until the pool is gone:
     *
     * <ol>
     *   <li>every boat that ran out of time (DNF);</li>
     *   <li>the last boat home, then the second last, and so on up the fleet, stopping
     *       short of the penalty places.</li>
     * </ol>
     *
     * <p>Read as a ladder, a big night is {@code +5 +4 +3 +2 +1 0 … 0 −1 … −1}: the club's
     * penalty ladder with its mirror image at the back. That is the point of it — the
     * committee asked for an answer they can check by eye, without sub-minute accounting.
     *
     * <p>A penalty place draws nothing, for the same reason as always: the ladder is the
     * club's statement of what a good result costs, and giving part of it back in the same
     * breath makes the printed 5, 4, 3, 2, 1 a fiction.
     *
     * <p><b>Nobody who raced gets more than a minute back.</b> Three edges follow from
     * that:
     *
     * <ul>
     *   <li><b>More DNFs than minutes.</b> There is no fair way to choose which of them
     *       gets one, so they share the pool evenly and nobody else gets anything.</li>
     *   <li><b>Minutes left when every boat out there has one.</b> A thin night. What the
     *       racers could not take goes evenly to the boats that stayed home, which is the
     *       2026 rule by another route: on a night most of the fleet missed, most of the
     *       pool goes to the fleet that did not have to be beaten. A DNC can get more
     *       than a minute, but only in a series too small for the ladder it is on.</li>
     *   <li><b>Minutes left and nobody at home.</b> They are discarded, and that race
     *       does not conserve. It needs the whole entry list out and too few of them
     *       behind the ladder — a night most of the fleet retired.</li>
     * </ul>
     *
     * <p>Nobody to receive at all — every boat that raced is on the ladder — is the
     * caller's case: nothing is charged.
     */
    private static double[] minuteGiveback(Kind[] kinds, double pool)
    {
        int n = kinds.length;
        double[] out = new double[n];

        List<Integer> dnfs = new ArrayList<>();
        List<Integer> finishersFromTheBack = new ArrayList<>();
        List<Integer> dncs = new ArrayList<>();
        for (int i = n - 1; i >= 0; i--)
        {
            switch (kinds[i])
            {
                case DNF -> dnfs.add(0, i);
                case FINISHER -> finishersFromTheBack.add(i);
                case DNC -> dncs.add(0, i);
                case PENALISED -> { }
            }
        }
        List<Integer> receivers = new ArrayList<>(dnfs);
        receivers.addAll(finishersFromTheBack);

        // The ladder is whole minutes (JinxConfig.Algorithm rounds it), so the pool is too.
        long minutes = Math.round(pool);
        if (minutes <= 0)
            return out;

        if (receivers.size() < minutes && !dncs.isEmpty())
        {
            for (int i : receivers)
                out[i] = 1.0;
            double left = pool - receivers.size();
            for (int i : dncs)
                out[i] = left / dncs.size();
            return out;
        }

        // Nobody at home, or the pool fits: a minute each until it is gone, and what the
        // racers cannot take is discarded.
        if (dnfs.size() > minutes)
        {
            for (int i : dnfs)
                out[i] = pool / dnfs.size();
            return out;
        }
        for (int k = 0; k < Math.min(minutes, receivers.size()); k++)
            out[receivers.get(k)] = 1.0;
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
