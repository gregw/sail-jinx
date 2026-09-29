package org.mortbay.sailing.jinx.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mortbay.sailing.jinx.config.JinxConfig.Algorithm;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

/**
 * Every key the algorithm block has ever had still loads. The club's config.yaml and its
 * saved series overrides carry them, and retiring a setting must not stop a race night.
 *
 * <p>The weighted giveback's keys — {@code variant}, {@code penaltyScaling},
 * {@code givebackGamma}, {@code dnfWeight}, {@code dncWeight} — went with the minute
 * giveback; that model is on the {@code weighted-giveback} branch.
 */
class RetiredAlgorithmKeysTest
{
    private static final String EVERY_RETIRED_KEY = """
          variant: B
          penaltyScaling: perHour
          givebackGamma: 0.5
          dnfWeight: 1.2
          dncWeight: 0.2
          givebackFleet: 0.33
          dnfAllowance: 5
          dnfInRaceDuration: true
          idealRaceLength: 75
          v0knots: 4
        """;

    @Test
    void theYamlStillLoads(@TempDir Path tmp) throws IOException
    {
        Path file = tmp.resolve("config.yaml");
        Files.writeString(file, "club:\n  domain: \"myc.org.au\"\nalgorithm:\n"
            + EVERY_RETIRED_KEY + "  penaltyList: [5, 3, 1]\n");
        Algorithm a = JinxConfig.load(file).algorithm();
        assertThat(a.penaltyList(), equalTo(List.of(5.0, 3.0, 1.0)));
        assertThat(a.defaultRaceDuration(), equalTo(75));
    }

    /**
     * A series override comes back through the servlet's mapper, which does not ignore
     * unknown properties — so the record has to, on its own.
     */
    @Test
    void aSavedSeriesOverrideStillLoadsThroughAStrictMapper() throws IOException
    {
        String json = """
            {"penaltyList":[5.0,4.0,3.0,2.0,1.0],"defaultRaceDuration":90,
             "penaltyScaling":"PER_HOUR","givebackGamma":0.0,"variant":"C",
             "dnfWeight":1.2,"dncWeight":0.2,"earliestStart":"18:00"}
            """;
        Algorithm a = JsonMapper.builder().build().readValue(json, Algorithm.class);
        assertThat(a.penaltyList(), equalTo(List.of(5.0, 4.0, 3.0, 2.0, 1.0)));
    }
}
