package org.mortbay.sailing.jinx.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Root configuration loaded from {@code data/config/config.yaml} at startup:
 * the club identity, the algorithm defaults, and the server port.
 *
 * <p>Unknown properties are ignored, so a config file left over from the
 * SailSys era (with its {@code sailsys:} block of club ids, handicap definition
 * ids and credentials) still loads — those settings simply have nowhere to go
 * any more.
 */
public record JinxConfig(
    Club club,
    Algorithm algorithm,
    Server server)
{
    private static final Logger LOG = LoggerFactory.getLogger(JinxConfig.class);

    private static final JsonMapper YAML_MAPPER = JsonMapper.builder(new YAMLFactory())
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .build();

    public JinxConfig
    {
        if (club == null)
            club = new Club(null, null, null, null);
        if (algorithm == null)
            algorithm = new Algorithm(null, 0, 0, null, null, null, false);
        if (server == null)
            server = new Server(0);
    }

    public static JinxConfig load(Path configFile) throws IOException
    {
        if (!Files.exists(configFile))
            throw new IOException("Config file not found: " + configFile.toAbsolutePath());
        LOG.info("Loading config from {}", configFile.toAbsolutePath());
        return YAML_MAPPER.readValue(Files.readAllBytes(configFile), JinxConfig.class);
    }

    /**
     * Who this installation belongs to.
     *
     * <p>{@code domain} is the club's identity, not decoration: series and race ids are
     * scoped by it ({@code myc.org.au/2026-winter-twilight}). A domain name is globally
     * unique, readable, and independent of any source system — which matters because club
     * names are not unique nationally. sailing-pf keys clubs the same way, so records
     * about the same club line up across both.
     *
     * <p>Changing it after data exists would orphan every series and race id, so it is
     * set once at installation.
     *
     * <p>{@code timezone} is the other field with teeth:
     * {@link org.mortbay.sailing.jinx.pursuit.SolarTimes} uses it to turn a computed
     * sunset into local wall-clock, which keeps the summer-DST evening races honest.
     */
    public record Club(
        @JsonProperty("domain") String domain,
        @JsonProperty("shortName") String shortName,
        @JsonProperty("longName") @JsonAlias("name") String longName,
        @JsonProperty("timezone") String timezone)
    {
        public Club
        {
            if (domain == null || domain.isBlank())
                domain = "club.invalid";
            if (longName == null || longName.isBlank())
                longName = "Sailing Club";
            if (shortName == null || shortName.isBlank())
                shortName = longName;
            if (timezone == null || timezone.isBlank())
                timezone = "Australia/Sydney";
        }
    }

    /**
     * Parameters for the Jinx pursuit handicap engine. Defaults are tuned to
     * the originating MYC Twilight use case; another club overrides via
     * {@code config.yaml}, and a single series can override further via the
     * Series Configure form (stored per-series in
     * {@code data/store/series-config/{seriesId}.json}).
     *
     * <p>{@code limitBySunset} caps the race duration so the slowest boat is expected
     * to finish by sunset on the race date.
     *
     * <p>There was a {@code v0knots} here — V₀, the speed of a notional 1.000-TCF boat.
     * It had two jobs and has neither. Sizing a course from a target duration went when
     * course length did; and in the post-race TCF conversion it cancelled out, because
     * {@code D_race} was derived from it and then divided by it again. A setting that
     * cannot change an answer is worse than no setting: somebody tunes it and believes
     * the result. Old files that still carry the key load fine — both mappers ignore
     * unknown properties.
     */
    public record Algorithm(
        @JsonProperty("penaltyList") List<Double> penaltyList,
        @JsonProperty("idealRaceDuration") @JsonAlias("idealRaceLength") int idealRaceDuration,
        @JsonProperty("dnfAllowance") int dnfAllowance,
        @JsonProperty("earliestStart") String earliestStart,
        @JsonProperty("latitude") Double latitude,
        @JsonProperty("longitude") Double longitude,
        @JsonProperty("limitBySunset") boolean limitBySunset)
    {
        public Algorithm
        {
            if (penaltyList == null || penaltyList.isEmpty())
                penaltyList = List.of(5.0, 4.0, 3.0, 2.0, 1.0);
            if (idealRaceDuration <= 0)
                idealRaceDuration = 90;
            if (dnfAllowance <= 0)
                dnfAllowance = 5;
            if (earliestStart == null || earliestStart.isBlank())
                earliestStart = "18:00";
            if (latitude == null)
                latitude = -33.8000;
            if (longitude == null)
                longitude = 151.2833;
        }
    }

    public record Server(int port)
    {
        public Server
        {
            if (port <= 0) port = 8080;
        }
    }
}
