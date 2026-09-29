package org.mortbay.sailing.jinx.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
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
            club = new Club(null, null, null, null, null, null, null, null);
        if (algorithm == null)
            algorithm = new Algorithm(null, 0, null, null, null, false);
        if (server == null)
            server = new Server(0, false);
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
     * <p>{@code website}, {@code otherResults}, {@code seriesEntry} and
     * {@code noticeBoard} are the club's own addresses, shown on the front page. They
     * are configuration rather than code because this application is not MYC's: another
     * club runs it against its own YAML, and a link hard-coded here would be a link to
     * somebody else's noticeboard. Each is optional and absent means the front page says
     * nothing about it.
     *
     * <p>{@code timezone} is the other field with teeth:
     * {@link org.mortbay.sailing.jinx.pursuit.SolarTimes} uses it to turn a computed
     * sunset into local wall-clock, which keeps the summer-DST evening races honest.
     */
    public record Club(
        @JsonProperty("domain") String domain,
        @JsonProperty("shortName") String shortName,
        @JsonProperty("longName") @JsonAlias("name") String longName,
        @JsonProperty("timezone") String timezone,
        @JsonProperty("website") String website,
        @JsonProperty("otherResults") String otherResults,
        @JsonProperty("seriesEntry") String seriesEntry,
        @JsonProperty("noticeBoard") String noticeBoard)
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
            // The four links are left null when they are not given. Every other field
            // here has a sensible fallback because something has to be printed; a link
            // does not — the front page leaves the sentence out rather than sending
            // somebody to an address nobody chose. A blank in YAML means the same as
            // absent, or a club that half-filled the file would publish a dead anchor.
            website = trimToNull(website);
            otherResults = trimToNull(otherResults);
            seriesEntry = trimToNull(seriesEntry);
            noticeBoard = trimToNull(noticeBoard);
        }

        private static String trimToNull(String s)
        {
            return (s == null || s.isBlank()) ? null : s.trim();
        }
    }

    /**
     * Parameters for the Jinx pursuit handicap engine: the club defaults from
     * {@code config.yaml}, which a series may override on its Handicap settings form
     * (stored in {@code data/store/series-config/{seriesId}.json}). How the ladder is
     * charged and given back is {@code PursuitHandicapEngine.minuteGiveback}.
     *
     * <p><b>{@code penaltyList} is whole minutes.</b> The giveback returns the pool a
     * minute at a time, so a fractional rung would leave a fraction nobody can be handed.
     * A fractional figure is rounded half-up with a warning rather than refused, so one bad
     * character in a YAML file does not stop a race night.
     *
     * <p>{@code defaultRaceDuration} is given to each new race as its expected duration,
     * and is the fallback for a race without one. That duration sets the stagger and is
     * what a time adjustment is measured against when it becomes a TCF change. It accepts
     * the old names {@code idealRaceDuration} and {@code idealRaceLength}.
     *
     * <p><b>Retired keys still load and are ignored:</b> {@code variant},
     * {@code penaltyScaling}, {@code givebackGamma}, {@code dnfWeight} and
     * {@code dncWeight} (the weighted giveback, kept on the {@code weighted-giveback}
     * branch), and before them {@code dnfAllowance}, {@code givebackFleet},
     * {@code dnfInRaceDuration} and {@code v0knots}. That is why this record carries
     * {@code @JsonIgnoreProperties} of its own rather than relying on the YAML mapper's
     * setting: a series override comes back through the servlet's mapper, which does
     * <em>not</em> ignore unknown properties, and an override saved before a key was
     * retired would otherwise fail to load.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Algorithm(
        @JsonProperty("penaltyList") List<Double> penaltyList,
        @JsonProperty("defaultRaceDuration")
        @JsonAlias({"idealRaceDuration", "idealRaceLength"}) int defaultRaceDuration,
        @JsonProperty("earliestStart") String earliestStart,
        @JsonProperty("latitude") Double latitude,
        @JsonProperty("longitude") Double longitude,
        @JsonProperty("limitBySunset") boolean limitBySunset)
    {
        public Algorithm
        {
            if (penaltyList == null || penaltyList.isEmpty())
                penaltyList = List.of(5.0, 4.0, 3.0, 2.0, 1.0);
            if (penaltyList.stream().anyMatch(p -> p == null || p != Math.rint(p)))
            {
                LOG.warn("algorithm.penaltyList {} is not whole minutes — rounding",
                    penaltyList);
                penaltyList = penaltyList.stream()
                    .map(p -> p == null ? 0.0 : (double)Math.round(p))
                    .toList();
            }
            if (defaultRaceDuration <= 0)
                defaultRaceDuration = 90;
            if (earliestStart == null || earliestStart.isBlank())
                earliestStart = "18:00";
            if (latitude == null)
                latitude = -33.8000;
            if (longitude == null)
                longitude = 151.2833;
        }
    }

    /**
     * The listener.
     *
     * <p>{@code forwardedHeaders} makes Jetty reconstruct the externally-visible URL from
     * {@code X-Forwarded-*} / {@code Forwarded} headers. Turn it on when — and only when —
     * something else terminates the connection: nginx, Apache, a load balancer.
     *
     * <p>It matters here for one specific reason. The OAuth {@code redirect_uri} is built
     * from the request, so behind a proxy without this the server sends Google back to
     * {@code http://localhost:8080/auth/callback} — which is not the address registered in
     * the console, and the login fails with a redirect-URI mismatch that looks like a
     * configuration error at Google's end.
     *
     * <p>Off by default, and it must stay off when the server is directly exposed: those
     * headers are just headers, and a client that sets its own would be deciding what the
     * server thinks its own address is.
     *
     * <p>{@code requestLog} is <b>on</b> by default and writes one line per request to the
     * ordinary log — the journal, under systemd. It is on because the alternative was
     * discovered the hard way: with only the application's own logging, a failing sign-in
     * showed the attempt but not what the browser had asked for or been told, and the
     * useful facts had to be inferred from the browser's address bar.
     */
    public record Server(
        @JsonProperty("port") int port,
        @JsonProperty("forwardedHeaders") boolean forwardedHeaders,
        @JsonProperty("requestLog") Boolean requestLog)
    {
        public Server
        {
            if (port <= 0) port = 8080;
            // Boolean rather than boolean, and defaulted here: an absent YAML key
            // deserialises a primitive to false, which would make "say nothing" the
            // default for the one setting whose whole purpose is to say something.
            // The compact constructor is what makes the accessor safe to unbox.
            if (requestLog == null) requestLog = Boolean.TRUE;
        }

        public Server(int port)
        {
            this(port, false);
        }

        public Server(int port, boolean forwardedHeaders)
        {
            this(port, forwardedHeaders, Boolean.TRUE);
        }
    }
}
