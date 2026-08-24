package org.mortbay.sailing.jinx.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonCreator;
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
            algorithm = new Algorithm(
                null, 0, 0, null, null, null, false, null, null, null, false);
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
     * How a penalty scales with the length of the race that earned it.
     *
     * <p>{@code FIXED} takes the figure from {@code penaltyList} as it stands, so the
     * same win costs the same on a 45-minute night as on a two-hour one. {@code PER_HOUR}
     * reads it as a rate and multiplies by the measured duration, so a win costs in
     * proportion to the racing it took.
     */
    public enum PenaltyScaling
    {
        FIXED,
        PER_HOUR;

        /** Tolerant of how a person writes it: perHour, per_hour, PERHOUR, per-hour. */
        @JsonCreator
        public static PenaltyScaling parse(String raw)
        {
            if (raw == null)
                return null;
            String v = raw.trim().toLowerCase(Locale.ENGLISH).replaceAll("[^a-z]", "");
            return switch (v)
            {
                case "fixed" -> FIXED;
                case "perhour" -> PER_HOUR;
                default ->
                {
                    LOG.warn("Unknown algorithm.penaltyScaling '{}' — using {}",
                        raw, DEFAULT_VARIANT.penaltyScaling());
                    yield null;
                }
            };
        }
    }

    /**
     * The four handicap variants, as the two knobs they actually are.
     *
     * <pre>
     *   Variant | penaltyScaling | givebackGamma
     *      A    | fixed          | 0.0
     *      B    | fixed          | 1.0   &lt;-- default
     *      C    | perHour        | 0.0
     *      D    | perHour        | 1.0
     * </pre>
     *
     * <p>A convenience only. The knobs are what the engine reads, and either may be set
     * on its own; naming a variant is a shorthand for setting both. Gamma is continuous,
     * so A/B/C/D are corners of a square rather than a list of alternatives.
     *
     * <p>γ = 0 splits the penalty pool evenly; γ = 1 shares it by how far behind the
     * leader each boat finished, so the first boat home gets nothing back. In between is
     * a genuine blend — see {@code PursuitHandicapEngine.givebacks}.
     */
    public enum Variant
    {
        A(PenaltyScaling.FIXED, 0.0),
        B(PenaltyScaling.FIXED, 1.0),
        C(PenaltyScaling.PER_HOUR, 0.0),
        D(PenaltyScaling.PER_HOUR, 1.0);

        private final PenaltyScaling penaltyScaling;
        private final double givebackGamma;

        Variant(PenaltyScaling penaltyScaling, double givebackGamma)
        {
            this.penaltyScaling = penaltyScaling;
            this.givebackGamma = givebackGamma;
        }

        public PenaltyScaling penaltyScaling() { return penaltyScaling; }

        public double givebackGamma() { return givebackGamma; }

        @JsonCreator
        public static Variant parse(String raw)
        {
            if (raw == null || raw.isBlank())
                return null;
            String v = raw.trim().toUpperCase(Locale.ENGLISH);
            try
            {
                return valueOf(v);
            }
            catch (IllegalArgumentException e)
            {
                LOG.warn("Unknown algorithm.variant '{}' — using {}", raw, DEFAULT_VARIANT);
                return null;
            }
        }
    }

    /** What the club gets when it says nothing: fixed penalties, shared by finish gap. */
    public static final Variant DEFAULT_VARIANT = Variant.B;

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
     * <p>{@code variant} is shorthand for the two knobs below it and is resolved away
     * here — the engine never sees it. An explicitly given knob wins over the variant
     * that disagrees with it, with a warning, because the specific setting is the one
     * somebody went to the trouble of writing.
     *
     * <p>{@code dnfAllowance} is how far past the last finisher a boat that retired is
     * scored, in minutes. One minute, not five: the knob now does two jobs on very
     * different scales. Against a 90-minute elapsed time five minutes is a nudge, but
     * against the <em>gap</em> the giveback shares by — a fleet finishing within ten
     * minutes of each other — five minutes was larger than the whole fleet's spread, and
     * two retirements took most of the pool between them.
     *
     * <p>{@code dnfInRaceDuration} decides whether boats that retired contribute their
     * allowance-derived elapsed time to the measured duration. Off by default: a stormy
     * night is exactly when retirements cluster, and their times are an allowance rather
     * than a measurement, so letting them in would stretch the very number the penalties
     * are scaled by.
     *
     * <p>{@code defaultRaceDuration} is the fallback <em>pre-race</em> target: how long a
     * race is meant to take when nobody has said, used only to compute published start
     * times. It is not the measured duration the handicap arithmetic runs on — that is
     * the median of what the fleet actually sailed, and the two must not be confused.
     *
     * <p>It carries the old name {@code idealRaceDuration} as an alias, because that key
     * held this value too. Its other job is gone: γ used to be derived from it, as
     * {@code t_target / (t_target + ideal)}. γ is an explicit knob now. One key doing a
     * shaping constant and a default target at once is how the two got conflated.
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
        @JsonProperty("defaultRaceDuration")
        @JsonAlias({"idealRaceDuration", "idealRaceLength"}) int defaultRaceDuration,
        @JsonProperty("dnfAllowance") int dnfAllowance,
        @JsonProperty("earliestStart") String earliestStart,
        @JsonProperty("latitude") Double latitude,
        @JsonProperty("longitude") Double longitude,
        @JsonProperty("limitBySunset") boolean limitBySunset,
        @JsonProperty("variant") Variant variant,
        @JsonProperty("penaltyScaling") PenaltyScaling penaltyScaling,
        @JsonProperty("givebackGamma") Double givebackGamma,
        @JsonProperty("dnfInRaceDuration") boolean dnfInRaceDuration)
    {
        public Algorithm
        {
            if (penaltyList == null || penaltyList.isEmpty())
                penaltyList = List.of(5.0, 4.0, 3.0, 2.0, 1.0);
            if (defaultRaceDuration <= 0)
                defaultRaceDuration = 90;
            if (dnfAllowance <= 0)
                dnfAllowance = 1;
            if (earliestStart == null || earliestStart.isBlank())
                earliestStart = "18:00";
            if (latitude == null)
                latitude = -33.8000;
            if (longitude == null)
                longitude = 151.2833;

            // Resolve the variant away, so everything downstream reads two plain knobs.
            Variant base = variant != null ? variant : DEFAULT_VARIANT;
            if (variant != null && penaltyScaling != null
                && penaltyScaling != variant.penaltyScaling())
            {
                LOG.warn("algorithm.variant {} says penaltyScaling {}, but penaltyScaling "
                    + "is set to {} — the explicit setting wins",
                    variant, variant.penaltyScaling(), penaltyScaling);
            }
            if (variant != null && givebackGamma != null
                && Double.compare(givebackGamma, variant.givebackGamma()) != 0)
            {
                LOG.warn("algorithm.variant {} says givebackGamma {}, but givebackGamma "
                    + "is set to {} — the explicit setting wins",
                    variant, variant.givebackGamma(), givebackGamma);
            }
            if (penaltyScaling == null)
                penaltyScaling = base.penaltyScaling();
            if (givebackGamma == null)
                givebackGamma = base.givebackGamma();
            // γ blends between "even" and "shared by the gap behind the leader". Outside
            // 0..1 it is not a stronger opinion, it is a typo, so it is clamped rather
            // than obeyed: a γ above 1 would give the leader a negative share and take
            // time off the boats that finished behind it.
            if (givebackGamma < 0.0 || givebackGamma > 1.0)
            {
                LOG.warn("algorithm.givebackGamma {} is outside 0.0..1.0 — clamping",
                    givebackGamma);
                givebackGamma = Math.min(1.0, Math.max(0.0, givebackGamma));
            }
        }

        /** The knobs as the variant they correspond to, or empty at an intermediate γ. */
        public Optional<Variant> asVariant()
        {
            for (Variant v : Variant.values())
            {
                if (v.penaltyScaling() == penaltyScaling
                    && Double.compare(v.givebackGamma(), givebackGamma) == 0)
                    return Optional.of(v);
            }
            return Optional.empty();
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
