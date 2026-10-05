package org.mortbay.sailing.jinx.pursuit;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mortbay.sailing.jinx.config.JinxConfig;
import org.mortbay.sailing.jinx.model.Adjustment;
import org.mortbay.sailing.jinx.model.FinishStatus;
import org.mortbay.sailing.jinx.model.Race;
import org.mortbay.sailing.jinx.model.Result;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.closeTo;
import static org.hamcrest.Matchers.is;

/**
 * The worked scenarios of wiki §6.3.3: a thirty-boat entry list on the 5, 4, 3, 2, 1
 * ladder, one of them the duty boat. If this fails, the table in the wiki is wrong.
 */
class ThirtyBoatFleetTest
{
    private static final List<Double> PENALTIES = List.of(5.0, 4.0, 3.0, 2.0, 1.0);

    /**
     * Thirty boats in this order: finishers one minute apart, then DNF, DNS, RET, one AVG,
     * and DNC for the rest. Returns each boat's net minutes, in that order.
     */
    private static double[] night(int finishers, int dnf, int dns, int ret)
    {
        List<Competitor> boats = new ArrayList<>();
        Map<String, Result> results = new LinkedHashMap<>();
        for (int i = 0; i < 30; i++)
        {
            String id = "b" + i;
            boats.add(new Competitor(id, 1.0, true));
            FinishStatus status;
            int k = i - finishers;
            if (k < 0)
            {
                int secs = (60 + i) * 60;
                results.put(id, new Result(id, FinishStatus.FIN, LocalTime.MIDNIGHT,
                    LocalTime.MIDNIGHT.plusSeconds(secs), null, i + 1, secs));
                continue;
            }
            if (k < dnf) status = FinishStatus.DNF;
            else if (k < dnf + dns) status = FinishStatus.DNS;
            else if (k < dnf + dns + ret) status = FinishStatus.RET;
            else if (k == dnf + dns + ret) status = FinishStatus.AVG;
            else status = FinishStatus.DNC;
            results.put(id, new Result(id, status, null, null, null));
        }
        double[] out = new double[30];
        for (Adjustment a : new PursuitHandicapEngine(
            new JinxConfig.Algorithm(PENALTIES, 90, "18:00", -33.8, 151.2833, false))
            .processResults(boats, new Race("r1", "s1", 1, "R1", LocalDate.of(2026, 5, 1),
                LocalTime.of(18, 0), 90, false), results, null))
            out[Integer.parseInt(a.boatId().substring(1))] = a.netAdjustmentMinutes();
        return out;
    }

    /** Asserts boats {@code from} to {@code to} inclusive (zero-based) all netted {@code net}. */
    private static void range(double[] nets, int from, int to, double net)
    {
        for (int i = from; i <= to; i++)
            assertThat("b" + i, nets[i], is(closeTo(net, 1e-9)));
    }

    private static void ladder(double[] nets)
    {
        for (int i = 0; i < 5; i++)
            assertThat("b" + i, nets[i], is(closeTo(PENALTIES.get(i), 1e-9)));
    }

    @Test
    void everybodyOutAndHome()
    {
        double[] n = night(29, 0, 0, 0);
        ladder(n);
        range(n, 5, 13, 0.0);   // 6th–14th
        range(n, 14, 28, -1.0); // 15th–29th
        range(n, 29, 29, 0.0);  // AVG
    }

    @Test
    void twentyFiveOutTwoDnf()
    {
        double[] n = night(23, 2, 0, 0);
        ladder(n);
        range(n, 5, 9, 0.0);    // 6th–10th
        range(n, 10, 22, -1.0); // 11th–23rd
        range(n, 23, 24, -1.0); // DNF
        range(n, 25, 29, 0.0);  // AVG, 4 DNC
    }

    @Test
    void twentyOut()
    {
        double[] n = night(20, 0, 0, 0);
        ladder(n);
        range(n, 5, 19, -1.0);
        range(n, 20, 29, 0.0);  // AVG, 9 DNC
    }

    @Test
    void nineteenOut()
    {
        double[] n = night(19, 0, 0, 0);
        ladder(n);
        range(n, 5, 18, -1.0);
        range(n, 19, 19, -1.0); // AVG
        range(n, 20, 29, 0.0);  // 10 DNC
    }

    @Test
    void nineOutWithADnsARetirementAndADnf()
    {
        double[] n = night(6, 1, 1, 1);
        ladder(n);
        range(n, 5, 5, -1.0);   // 6th
        range(n, 6, 6, -1.0);   // DNF
        range(n, 7, 8, -1.0);   // DNS, RET
        range(n, 9, 9, -1.0);   // AVG
        range(n, 10, 29, -0.5); // 20 DNC share 10
    }

    @Test
    void twentyOutSixteenDnf()
    {
        double[] n = night(2, 16, 2, 0);
        assertThat(n[0], is(closeTo(5.0, 1e-9)));
        assertThat(n[1], is(closeTo(4.0, 1e-9)));
        range(n, 2, 17, -9.0 / 16.0); // 16 DNF share 9
        range(n, 18, 29, 0.0);        // 2 DNS, AVG, 9 DNC
    }
}
