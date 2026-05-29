package org.mortbay.sailing.jinx.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Root configuration loaded from {@code data/config/config.yaml} at startup.
 * Credentials, series IDs, algorithm parameters and the server port live here.
 * <p>
 * The file is never committed: a {@code config.example.yaml} ships with the project
 * and the operator copies it locally to inject credentials.
 */
public record JinxConfig(
    SailSys sailsys,
    Algorithm algorithm,
    Server server)
{
    private static final Logger LOG = LoggerFactory.getLogger(JinxConfig.class);

    private static final JsonMapper YAML_MAPPER = JsonMapper.builder(new YAMLFactory())
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .build();

    public static JinxConfig load(Path configFile) throws IOException
    {
        if (!Files.exists(configFile))
            throw new IOException("Config file not found: " + configFile.toAbsolutePath());
        LOG.info("Loading config from {}", configFile.toAbsolutePath());
        return YAML_MAPPER.readValue(Files.readAllBytes(configFile), JinxConfig.class);
    }

    /**
     * Connection settings for the SailSys API.
     *
     * <p>Credentials are deliberately NOT part of this record — the race
     * officer supplies them via the login form on each browser session, the
     * server uses them once to mint a SailSys session token, and then discards
     * them. They are never persisted.
     *
     * <p>The active <em>series</em> is also not part of this record — sail-jinx
     * is multi-series. The Series tab lists every series the club runs; the
     * operator picks one at runtime. Only the {@code clubId} (which scopes the
     * series list) and {@code handicapDefinitionId} (which identifies the PHS
     * handicap line) are club-wide configuration.
     */
    public record SailSys(
        @JsonProperty("clubId") int clubId,
        @JsonProperty("handicapDefinitionId") Integer handicapDefinitionId,
        String timezone,
        Integer timezoneOffset)
    {
        public SailSys
        {
            if (timezone == null) timezone = "Australia/Sydney";
            if (timezoneOffset == null) timezoneOffset = 10;
            if (handicapDefinitionId == null) handicapDefinitionId = 5; // PHS
        }
    }

    /**
     * Algorithm parameters used by the Jinx pursuit handicap engine. Defaults
     * are tuned to the originating MYC Twilight use case; another club
     * overrides via {@code config.yaml}, and a single series can override
     * further via the Series Configure form (stored per-series in
     * {@code data/store/series-config/{seriesId}.json}).
     *
     * <p>{@code latitude}/{@code longitude} default to MYC (Spit-mouth-ish);
     * {@code limitBySunset} is the toggle for an upcoming feature that caps
     * the target race length so the slowest boat is expected to finish before
     * sunset on the race date.
     *
     * <p>TODO: when {@code limitBySunset} is wired into the engine, fetch
     * sunset wall-clock for the race date via
     * <pre>
     *   GET https://api.sunrise-sunset.org/json?lat={lat}&lng={lng}&date={YYYY-MM-DD}&formatted=0
     * </pre>
     * The {@code results.sunset} field in the response is ISO-8601 UTC and
     * can be converted to local time using {@link SailSys#timezone()}.
     */
    public record Algorithm(
        @JsonProperty("penaltyList") List<Integer> penaltyList,
        @JsonProperty("idealRaceLength") int idealRaceLength,
        @JsonProperty("dnfAllowance") int dnfAllowance,
        @JsonProperty("earliestStart") String earliestStart,
        @JsonProperty("latitude") Double latitude,
        @JsonProperty("longitude") Double longitude,
        @JsonProperty("limitBySunset") boolean limitBySunset)
    {
        public Algorithm
        {
            if (penaltyList == null || penaltyList.isEmpty())
                penaltyList = List.of(5, 4, 3, 2, 1);
            if (idealRaceLength <= 0)
                idealRaceLength = 90;
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
