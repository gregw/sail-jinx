package org.mortbay.sailing.jinx.pursuit;

import java.time.Duration;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import org.mortbay.sailing.jinx.config.JinxConfig;
import org.mortbay.sailing.jinx.model.Adjustment;
import org.mortbay.sailing.jinx.model.Boat;
import org.mortbay.sailing.jinx.model.Calibration;
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
    private final Calibration calibration;

    public PursuitHandicapEngine(JinxConfig.Algorithm config)
    {
        this(config, null);
    }

    /**
     * Calibration-aware constructor. When {@code calibration} is non-null,
     * {@code newTcf} is derived from V₀ and the race's actual course distance
     * (median over finishers of {@code TCF × V₀ × elapsed/60}) rather than
     * from the fleet-median TCF; the underlying mechanics are equivalent (both
     * solve {@code 1/newTcf − 1/oldTcf = −Δs × V₀ / (60·D)}), but the
     * V₀-anchored form lets the calculation be displayed and audited against
     * the saved SailSys calibration.
     */
    public PursuitHandicapEngine(JinxConfig.Algorithm config, Calibration calibration)
    {
        this.config = config;
        this.calibration = calibration;
    }

    @Override
    public List<StartTime> computeStartTimes(List<Boat> boats, Race race)
    {
        if (boats == null || boats.isEmpty())
            return List.of();

        int tTarget = race.targetElapsedMinutes() != null ? race.targetElapsedMinutes() : 60;
        LocalTime tEarliest = race.earliestStart() != null
            ? race.earliestStart()
            : LocalTime.parse(config.earliestStart());

        double tcfMed = median(boats.stream().map(Boat::currentTcf).toList());

        double[] tau = new double[boats.size()];
        double tauMax = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < boats.size(); i++)
        {
            tau[i] = tTarget * tcfMed / boats.get(i).currentTcf();
            if (tau[i] > tauMax) tauMax = tau[i];
        }

        List<StartTime> out = new ArrayList<>(boats.size());
        for (int i = 0; i < boats.size(); i++)
        {
            long minutesAfterEarliest = Math.round(tauMax - tau[i]);
            LocalTime startTime = tEarliest.plusMinutes(minutesAfterEarliest);
            out.add(new StartTime(boats.get(i).id(), boats.get(i).currentTcf(), tau[i], startTime));
        }
        return out;
    }

    @Override
    public List<Adjustment> processResults(List<Boat> boats, Race race, Map<String, Result> results)
    {
        // TODO: when config.limitBySunset() is true, fetch the local sunset
        //   wall-clock for race.date() and cap t_target so the slowest boat
        //   is expected to finish before sundown. Source:
        //     GET https://api.sunrise-sunset.org/json
        //         ?lat={config.latitude()}&lng={config.longitude()}
        //         &date={race.date()}&formatted=0
        //   The response's data.sunset field is ISO-8601 UTC — convert to the
        //   configured SailSys.timezone() before comparing to start time.
        //   Cache per-date since the API is rate-limited.

        int tTarget = race.targetElapsedMinutes() != null ? race.targetElapsedMinutes() : 90;

        // §5: classify by FinishStatus and assign effective elapsed time.
        // Carry through any per-result finishPosition so the engine can
        // assign penalties by official place rather than by raw-elapsed
        // sort. Position-less finishers fall back to elapsed-sort.
        record Entry(Boat boat, double elapsedMinutes, Integer position) {}
        List<Entry> finishers = new ArrayList<>();
        List<Boat> dnfRet = new ArrayList<>();
        List<Boat> excluded = new ArrayList<>();
        for (Boat b : boats)
        {
            Result r = results == null ? null : results.get(b.id());
            if (r == null)
            {
                excluded.add(b);
                continue;
            }
            switch (r.status())
            {
                case FIN ->
                {
                    Duration d = r.elapsed();
                    if (d == null)
                        excluded.add(b);
                    else
                        finishers.add(new Entry(b,
                            d.toMillis() / 60_000.0,
                            r.finishPosition()));
                }
                case DNF, RET -> dnfRet.add(b);
                default -> excluded.add(b); // DSQ, DNC, DNS
            }
        }
        // Sort key: finishPosition when supplied, else elapsed-time order.
        // A finishPosition of null is pushed to the end so position-bearing
        // finishers always sort before position-less ones — keeps the
        // mixed-supply case sane though we don't expect it in production.
        finishers.sort((a, c) -> {
            Integer ap = a.position(), cp = c.position();
            if (ap != null && cp != null) return Integer.compare(ap, cp);
            if (ap != null) return -1;
            if (cp != null) return 1;
            return Double.compare(a.elapsedMinutes(), c.elapsedMinutes());
        });

        double slowestFinisher = finishers.isEmpty()
            ? tTarget
            : finishers.stream().mapToDouble(Entry::elapsedMinutes).max().orElse(tTarget);
        double dnfElapsed = slowestFinisher + config.dnfAllowance();

        // Participating boats: finishers (in finish order), then DNF/RET.
        // Penalty draws from penaltyList by sorted position — when official
        // positions were supplied, that's the official finish order; else
        // elapsed-sort order.
        record Participant(Boat boat, Integer position, double elapsed, double penalty) {}
        List<Participant> participants = new ArrayList<>();
        for (int i = 0; i < finishers.size(); i++)
        {
            Entry e = finishers.get(i);
            int position = (e.position() != null) ? e.position() : (i + 1);
            int penaltyIdx = position - 1;
            double penalty = (penaltyIdx >= 0 && penaltyIdx < config.penaltyList().size())
                ? config.penaltyList().get(penaltyIdx)
                : 0.0;
            participants.add(new Participant(e.boat(), position, e.elapsedMinutes(), penalty));
        }
        for (Boat b : dnfRet)
            participants.add(new Participant(b, null, dnfElapsed, 0.0));

        // §6.1 — penalty pool actually awarded.
        double pool = participants.stream().mapToDouble(Participant::penalty).sum();

        // §6.3 — γ exponent and weighted rewards. Every participating boat
        // is weighted by its own elapsed^γ — boats that spent longer on
        // the course pick up a larger share of the pool. No "winner"
        // anchor: in pursuit the position-1 boat is the SLOWEST in
        // elapsed terms (it got the biggest head start), so an
        // (elapsed − e_winner) gap formula would invert the intended
        // behaviour and concentrate the pool on a single boat. The
        // simple elapsed^γ weighting works for both scratch (fastest
        // = smallest elapsed = smallest share) and pursuit (front of
        // fleet = smallest elapsed = smallest share, by definition of
        // how pursuit start offsets are computed).
        double gamma = (double) tTarget / (tTarget + config.idealRaceLength());
        double[] weights = new double[participants.size()];
        double weightSum = 0.0;
        for (int i = 0; i < participants.size(); i++)
        {
            weights[i] = Math.pow(participants.get(i).elapsed(), gamma);
            weightSum += weights[i];
        }

        // §7 — convert Δs back into TCF deltas. Two equivalent
        //   parameterisations:
        //     calibration-anchored (preferred when V₀ has been measured):
        //       D_race ≈ median over finishers of (TCF × V₀ × E/60) nm
        //       newTcf = oldTcf / (1 − net × oldTcf × V₀ / (60 × D_race))
        //     fleet-median fallback (no calibration available):
        //       newTcf = oldTcf / (1 − net × oldTcf / (tTarget × tcfMed))
        // No fleet-wide anchor correction is applied here: the next race's
        // start-time processing pass over the updated TCFs is what brings
        // the new slowest boat back to t_earliest.
        double tcfMed = participants.isEmpty()
            ? 1.0
            : median(participants.stream().map(p -> p.boat().currentTcf()).toList());

        // V₀-anchored "minutes for a 1.000 TCF boat over D_race". If no
        // finishers, the penalty pool is empty so all nets are zero and the
        // denominator is 1 regardless — any positive value works.
        double tMinutesPerUnitTcf;
        if (calibration != null)
        {
            double v0 = calibration.v0Knots();
            double dRaceNm;
            if (finishers.isEmpty())
            {
                dRaceNm = 1.0;
            }
            else
            {
                List<Double> dEstimates = new ArrayList<>(finishers.size());
                for (Entry f : finishers)
                    dEstimates.add(f.boat().currentTcf() * v0 * f.elapsedMinutes() / 60.0);
                dRaceNm = median(dEstimates);
            }
            tMinutesPerUnitTcf = 60.0 * dRaceNm / v0;
        }
        else
        {
            tMinutesPerUnitTcf = tTarget * tcfMed;
        }

        List<Adjustment> adjustments = new ArrayList<>(boats.size());
        for (int i = 0; i < participants.size(); i++)
        {
            Participant p = participants.get(i);
            double reward = weightSum > 0 ? pool * weights[i] / weightSum : 0.0;
            double net = p.penalty() - reward;
            double oldTcf = p.boat().currentTcf();
            double denom = 1.0 - net * oldTcf / tMinutesPerUnitTcf;
            double newTcf = (denom == 0.0) ? oldTcf : oldTcf / denom;
            adjustments.add(new Adjustment(
                p.boat().id(), p.position(), p.penalty(), reward, net, oldTcf, newTcf));
        }
        // Excluded boats appear in the result with zero deltas and a frozen TCF
        // so the audit/UI can still show them in the table.
        for (Boat b : excluded)
            adjustments.add(new Adjustment(b.id(), null, 0.0, 0.0, 0.0, b.currentTcf(), b.currentTcf()));

        return adjustments;
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
