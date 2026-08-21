package org.mortbay.sailing.jinx.pursuit;

import java.time.Duration;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
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

        List<StartTime> out = new ArrayList<>(boats.size());
        for (int i = 0; i < boats.size(); i++)
        {
            long minutesAfterEarliest = Math.round(tauMax - tau[i]);
            LocalTime startTime = tEarliest.plusMinutes(minutesAfterEarliest);
            out.add(new StartTime(boats.get(i).boatId(), boats.get(i).tcf(), tau[i], startTime));
        }
        return out;
    }

    @Override
    public List<Adjustment> processResults(List<Competitor> boats, Race race, Map<String, Result> results)
    {
        // The sunset cap is not applied here. It shapes the course the RO lays before the
        // race, not the handicap maths afterwards — by this point the boats have sailed
        // whatever course they were given, and their elapsed times say so.
        //
        // Note what is NOT read: race.targetElapsedMinutes(). That is the pre-race
        // estimate, used only to publish start times. Everything below is scaled by the
        // duration the fleet actually sailed. Substituting the target here would make the
        // handicap depend on a guess made before the race instead of on the race.

        // §5 — classify, and assign an effective elapsed time.
        record Entry(Competitor boat, double elapsedMinutes, Integer position) {}
        List<Entry> finishers = new ArrayList<>();
        List<Competitor> dnfRet = new ArrayList<>();
        List<Competitor> frozen = new ArrayList<>();
        for (Competitor b : boats)
        {
            // A boat that was not seeded raced tonight but takes no part in the handicap.
            if (!b.seeded())
            {
                frozen.add(b);
                continue;
            }
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
                case DNF, RET -> dnfRet.add(b);
                // DSQ, DNC, DNS: seeded, but with no time of their own. Frozen, and out
                // of the placings, the giveback and the measured duration alike.
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

        double slowestFinisher = finishers.stream()
            .mapToDouble(Entry::elapsedMinutes).max().orElse(0.0);
        double dnfElapsed = slowestFinisher + config.dnfAllowance();

        // §6.1 — the measured duration: the median of what the fleet actually sailed.
        // This one number scales the penalties AND anchors the TCF conversion below, and
        // it has to be the same number in both places — that identity is what makes the
        // per-hour, even-giveback case exactly duration-independent.
        List<Double> durationSample = new ArrayList<>();
        for (Entry f : finishers)
            durationSample.add(f.elapsedMinutes());
        if (config.dnfInRaceDuration())
        {
            for (int i = 0; i < dnfRet.size(); i++)
                durationSample.add(dnfElapsed);
        }
        // With nothing measured there is nothing to award: the pool below comes out empty,
        // every net is zero, and this value cannot reach an answer. It only has to be
        // positive so the §7 denominator stays defined.
        double raceDuration = durationSample.isEmpty() ? 60.0 : median(durationSample);

        // Participants: seeded finishers in finish order, then seeded DNF/RET.
        record Participant(Competitor boat, Integer position, double elapsed, double penalty) {}
        List<Participant> participants = new ArrayList<>();
        for (int i = 0; i < finishers.size(); i++)
        {
            Entry e = finishers.get(i);
            // The penalty ladder is drawn against rank among PARTICIPATING finishers, not
            // the official place. An unseeded boat is not in this race's handicap at all,
            // so it does not occupy a rung: if it finishes first, the first seeded boat
            // home still pays the first penalty. Adjustment keeps the official place for
            // display; only the ladder closes up.
            double penalty = penaltyForRank(i + 1, raceDuration);
            participants.add(new Participant(e.boat(),
                e.position() != null ? e.position() : (i + 1), e.elapsedMinutes(), penalty));
        }
        for (Competitor b : dnfRet)
            participants.add(new Participant(b, null, dnfElapsed, 0.0));

        double pool = participants.stream().mapToDouble(Participant::penalty).sum();

        // §6.3 — giveback over every participant.
        double[] rewards = givebacks(pool, participants.stream()
            .mapToDouble(Participant::elapsed).toArray());

        // §7 — net minutes back into TCF, against the same measured duration.
        //   newTcf = tcf / (1 − net × tcf / (raceDuration × tcfMed))
        // No fleet-wide anchor correction: the next race's start-time pass over the
        // updated TCFs is what brings the new slowest boat back to t_earliest.
        double tcfMed = median(participants.stream()
            .map(p -> p.boat().tcf()).toList());
        double scale = raceDuration * tcfMed;
        if (!(scale > 0.0))
        {
            throw new IllegalStateException(
                "handicap scale must be positive, but raceDuration=" + raceDuration
                    + " × medianTcf=" + tcfMed + " = " + scale
                    + " — cannot convert time adjustments into TCF changes");
        }

        List<Adjustment> adjustments = new ArrayList<>(boats.size());
        for (int i = 0; i < participants.size(); i++)
        {
            Participant p = participants.get(i);
            double net = p.penalty() - rewards[i];
            double oldTcf = p.boat().tcf();
            double denom = 1.0 - net * oldTcf / scale;
            if (!(denom > 0.0))
            {
                throw new IllegalStateException(
                    "TCF conversion denominator must be positive for " + p.boat().boatId()
                        + " but was " + denom + " (net=" + net + ", tcf=" + oldTcf
                        + ", raceDuration=" + raceDuration + ", medianTcf=" + tcfMed
                        + ") — a penalty this large against a race this short cannot be "
                        + "expressed as a handicap change");
            }
            adjustments.add(new Adjustment(p.boat().boatId(), p.position(),
                p.penalty(), rewards[i], net, oldTcf, oldTcf / denom));
        }
        // Frozen boats — unseeded, or seeded with no time — still get a row, with zero
        // deltas and their TCF untouched, so the audit and the table can show them.
        for (Competitor b : frozen)
            adjustments.add(new Adjustment(b.boatId(), null, 0.0, 0.0, 0.0, b.tcf(), b.tcf()));

        return adjustments;
    }

    /**
     * The penalty for finishing at {@code rank} (1-based), in minutes. Beyond the end of
     * the list it is zero — the list says how far down the fleet a placing is worth
     * paying for.
     *
     * <p>Under {@link JinxConfig.PenaltyScaling#PER_HOUR} the figure is a rate rather than
     * an amount, so it is multiplied by the measured duration. That is the whole
     * difference between variants A/B and C/D.
     */
    private double penaltyForRank(int rank, double raceDurationMinutes)
    {
        int idx = rank - 1;
        if (idx < 0 || idx >= config.penaltyList().size())
            return 0.0;
        double listed = config.penaltyList().get(idx);
        return config.penaltyScaling() == JinxConfig.PenaltyScaling.PER_HOUR
            ? listed * raceDurationMinutes / 60.0
            : listed;
    }

    /**
     * Share the pool back over the participants.
     *
     * <p>γ = 0 splits it evenly — every boat that turned up and raced gets the same
     * credit for being there. γ = 1 weights by elapsed time, so a boat that spent longer
     * on the course draws a larger share. In between is a blend, and legal.
     *
     * <p>The even split is computed directly rather than as {@code elapsed^0}: they agree
     * mathematically, but only if every elapsed time is positive, and a boat credited
     * with zero elapsed would make {@code 0^0} decide the fleet's handicaps.
     *
     * <p>There is deliberately no "winner" anchor. In a pursuit race the boat in first
     * place is the <em>slowest</em> in elapsed terms — it was given the biggest head
     * start — so a gap-from-the-winner formula would invert the intent and pile the pool
     * onto one boat.
     */
    private double[] givebacks(double pool, double[] elapsed)
    {
        int n = elapsed.length;
        double[] out = new double[n];
        if (n == 0 || pool == 0.0)
            return out;

        double gamma = config.givebackGamma();
        if (gamma == 0.0)
        {
            double even = pool / n;
            for (int i = 0; i < n; i++)
                out[i] = even;
            return out;
        }

        double weightSum = 0.0;
        double[] weights = new double[n];
        for (int i = 0; i < n; i++)
        {
            weights[i] = Math.pow(Math.max(0.0, elapsed[i]), gamma);
            weightSum += weights[i];
        }
        if (weightSum <= 0.0)
        {
            double even = pool / n;
            for (int i = 0; i < n; i++)
                out[i] = even;
            return out;
        }
        for (int i = 0; i < n; i++)
            out[i] = pool * weights[i] / weightSum;
        return out;
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
