package org.mortbay.sailing.jinx.pursuit;

import java.util.List;
import java.util.Map;

import org.mortbay.sailing.jinx.config.JinxConfig;
import org.mortbay.sailing.jinx.model.Adjustment;
import org.mortbay.sailing.jinx.model.Boat;
import org.mortbay.sailing.jinx.model.Race;
import org.mortbay.sailing.jinx.model.Result;
import org.mortbay.sailing.jinx.model.StartTime;

/**
 * MYC Twilight pursuit handicap, version 2.
 * Full specification: {@code wiki/myc-twilight-handicap-v2.md}.
 *
 * <p>Skeleton: the bodies are TODO. The algorithm is well-defined and unit
 * testable from the worked example in §6.5 of the spec.
 */
public class PursuitHandicapEngine implements HandicapEngine
{
    private final JinxConfig.Algorithm config;

    public PursuitHandicapEngine(JinxConfig.Algorithm config)
    {
        this.config = config;
    }

    @Override
    public List<StartTime> computeStartTimes(List<Boat> boats, Race race)
    {
        // TODO: implement per wiki §4.
        //   τᵢ = t_target_elapsed × TCF_med / TCFᵢ
        //   Δtᵢ = τ_max - τᵢ
        //   t_startᵢ = t_earliest_start + round(Δtᵢ)
        throw new UnsupportedOperationException("PursuitHandicapEngine.computeStartTimes — TODO");
    }

    @Override
    public List<Adjustment> processResults(List<Boat> boats, Race race, Map<String, Result> results)
    {
        // TODO: implement per wiki §5–§7.
        //   1. assign effective elapsed times (FIN → actual; DNF/RET → slowest + dnfAllowance;
        //      DSQ/DNC/DNS → exclude).
        //   2. γ = t_target / (t_target + idealRaceLength)
        //   3. weights wᵢ = eᵢ^γ; rewards = P × wᵢ / Σwᵢ
        //   4. fixed penalties from config.penaltyList
        //   5. Δsᵢ = penaltyᵢ - rewardᵢ
        //   6. new_TCFᵢ = TCFᵢ / (1 - Δsᵢ × TCFᵢ / (t_target × TCF_med))
        throw new UnsupportedOperationException("PursuitHandicapEngine.processResults — TODO");
    }
}
