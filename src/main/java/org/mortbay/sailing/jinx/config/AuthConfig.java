package org.mortbay.sailing.jinx.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Who may use this server, loaded from {@code data/config/auth.yaml}.
 *
 * <p>Kept in its own file, separate from {@code config.yaml}, for one reason: it holds a
 * client secret. {@code config.yaml} is committed; this is in {@code .gitignore} and must
 * stay there. {@code auth.yaml.example} beside it shows the shape without the secret.
 *
 * <p><b>Absent means off.</b> No file, or {@code enabled: false}, and the server behaves
 * exactly as it did before authentication existed — every request is an admin. That is
 * the right default for the machine on the office desk, and the wrong one for anything
 * with a network around it, which is what {@link #enabled} is for.
 *
 * <p>With it on, who is what — see {@code ApiServlet.Role}:
 * <ul>
 *   <li>an address in {@link #admins} is an admin;</li>
 *   <li>an address in {@link #raceOfficers}, or any account in one of the
 *       {@link #allowedDomains}, is a race officer;</li>
 *   <li>any other account the issuer signs in is merely signed in;</li>
 *   <li>and nobody signed in is a visitor.</li>
 * </ul>
 */
public record AuthConfig(
    boolean enabled,
    String issuer,
    String clientId,
    String clientSecret,
    String redirectPath,
    List<String> allowedDomains,
    List<String> admins,
    List<String> raceOfficers,
    boolean allowLoopback)
{
    private static final Logger LOG = LoggerFactory.getLogger(AuthConfig.class);

    private static final JsonMapper YAML_MAPPER = JsonMapper.builder(new YAMLFactory())
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .build();

    /** Google's OpenID Connect issuer. Everything else is discovered from it. */
    public static final String GOOGLE = "https://accounts.google.com";

    public AuthConfig
    {
        if (issuer == null || issuer.isBlank())
            issuer = GOOGLE;
        if (redirectPath == null || redirectPath.isBlank())
            redirectPath = "/auth/callback";
        if (!redirectPath.startsWith("/"))
            redirectPath = "/" + redirectPath;
        allowedDomains = lowerCased(allowedDomains);
        admins = lowerCased(admins);
        raceOfficers = lowerCased(raceOfficers);
    }

    /**
     * What {@code auth.yaml} is read through. {@code allowedDomain}, singular, is the key
     * every file written before there could be several has; it still counts.
     */
    @JsonCreator
    static AuthConfig fromYaml(
        @JsonProperty("enabled") boolean enabled,
        @JsonProperty("issuer") String issuer,
        @JsonProperty("clientId") String clientId,
        @JsonProperty("clientSecret") String clientSecret,
        @JsonProperty("redirectPath") String redirectPath,
        @JsonProperty("allowedDomain") String allowedDomain,
        @JsonProperty("allowedDomains") List<String> allowedDomains,
        @JsonProperty("admins") List<String> admins,
        @JsonProperty("raceOfficers") List<String> raceOfficers,
        @JsonProperty("allowLoopback") boolean allowLoopback)
    {
        List<String> domains = new java.util.ArrayList<>();
        if (allowedDomain != null)
            domains.add(allowedDomain);
        if (allowedDomains != null)
            domains.addAll(allowedDomains);
        return new AuthConfig(enabled, issuer, clientId, clientSecret, redirectPath,
            domains, admins, raceOfficers, allowLoopback);
    }

    private static List<String> lowerCased(List<String> values)
    {
        return values == null ? List.of()
            : values.stream()
                .filter(v -> v != null && !v.isBlank())
                .map(v -> v.trim().toLowerCase(Locale.ENGLISH))
                .distinct()
                .toList();
    }

    /** The off switch, for when there is no file at all. */
    public static AuthConfig disabled()
    {
        return new AuthConfig(false, null, null, null, null, List.of(), List.of(), List.of(),
            false);
    }

    /**
     * Load {@code auth.yaml} from the config directory, or return {@link #disabled()} if
     * it is not there.
     *
     * <p>A file that is present but unreadable is <em>not</em> treated as "off". Failing
     * open on a broken security config is how a server ends up unprotected for a week
     * without anybody noticing, so this throws and the server does not start.
     */
    public static AuthConfig load(Path configDir) throws IOException
    {
        Path file = configDir.resolve("auth.yaml");
        if (!Files.isRegularFile(file))
        {
            LOG.info("No {} — authentication is off, every request is an admin", file);
            return disabled();
        }
        AuthConfig auth = YAML_MAPPER.readValue(file.toFile(), AuthConfig.class);
        if (!auth.enabled())
        {
            LOG.warn("{} says enabled: false — authentication is off", file);
            return auth;
        }
        auth.requireUsable(file);
        LOG.info("Authentication on: {} accounts may sign in; race officers are {}{}{}",
            auth.issuer(),
            auth.allowedDomains().isEmpty() ? "" : "accounts in " + auth.allowedDomains()
                + (auth.raceOfficers().isEmpty() ? "" : " and "),
            auth.raceOfficers().isEmpty() ? "" : auth.raceOfficers().size() + " named",
            auth.allowLoopback() ? "; loopback exempt" : "");
        if (auth.admins().isEmpty())
            LOG.warn("{} names no admins — nobody signed in can change series, races or "
                + "the register", file);
        if (auth.allowedDomains().isEmpty() && auth.raceOfficers().isEmpty())
            LOG.warn("{} has no allowedDomains and no raceOfficers — only admins can run a "
                + "race", file);
        return auth;
    }

    /** Refuse to start half-configured rather than start unprotected. */
    private void requireUsable(Path file)
    {
        if (clientId == null || clientId.isBlank())
            throw new IllegalStateException(file + ": clientId is required when enabled");
        if (clientSecret == null || clientSecret.isBlank())
            throw new IllegalStateException(file + ": clientSecret is required when enabled");
    }

    /**
     * Whether this account runs race nights: named in {@code raceOfficers}, or in one of
     * the {@code allowedDomains}. Admins are checked separately and outrank this.
     *
     * <p>The domain is checked against the {@code hd} claim — the Workspace domain Google
     * itself asserts — falling back to the address. Note that the {@code hd} parameter on
     * the <em>request</em> is only a hint to Google's account chooser and is not a control;
     * the check has to happen here, on the claim that comes back.
     */
    public boolean isRaceOfficer(String email, String hostedDomain)
    {
        if (email == null || email.isBlank())
            return false;
        String address = email.trim().toLowerCase(Locale.ENGLISH);
        if (raceOfficers.contains(address))
            return true;
        for (String domain : allowedDomains)
        {
            if (hostedDomain != null && domain.equalsIgnoreCase(hostedDomain.trim()))
                return true;
            if (address.endsWith("@" + domain))
                return true;
        }
        return false;
    }

    /**
     * Whether this account is an admin — see {@code ApiServlet.Role}.
     *
     * <p>Only the addresses named in {@code admins}. An empty list once meant everyone
     * who could sign in was an admin; now that any Google account can sign in, that would
     * be an admin account for the world, so empty means nobody. With authentication off
     * everybody is an admin, as before.
     */
    public boolean isAdmin(String email)
    {
        if (!enabled)
            return true;
        return email != null
            && admins.contains(email.trim().toLowerCase(Locale.ENGLISH));
    }

    /** The admin addresses, for display. */
    public Set<String> adminSet()
    {
        return admins.stream().collect(Collectors.toUnmodifiableSet());
    }
}
