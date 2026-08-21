package org.mortbay.sailing.jinx.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

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
 */
public record AuthConfig(
    @JsonProperty("enabled") boolean enabled,
    @JsonProperty("issuer") String issuer,
    @JsonProperty("clientId") String clientId,
    @JsonProperty("clientSecret") String clientSecret,
    @JsonProperty("redirectPath") String redirectPath,
    @JsonProperty("allowedDomain") String allowedDomain,
    @JsonProperty("admins") List<String> admins,
    @JsonProperty("allowLoopback") boolean allowLoopback)
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
        admins = admins == null ? List.of()
            : admins.stream()
                .filter(a -> a != null && !a.isBlank())
                .map(a -> a.trim().toLowerCase(Locale.ENGLISH))
                .toList();
    }

    /** The off switch, for when there is no file at all. */
    public static AuthConfig disabled()
    {
        return new AuthConfig(false, null, null, null, null, null, List.of(), false);
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
        LOG.info("Authentication on: {} accounts in {}{}", auth.issuer(),
            auth.allowedDomain() == null ? "any domain" : auth.allowedDomain(),
            auth.allowLoopback() ? ", loopback exempt" : "");
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
     * Whether this signed-in account may use the server at all.
     *
     * <p>Checked against the {@code hd} claim — the Workspace domain Google itself
     * asserts — and falling back to the address. Note that the {@code hd} parameter on the
     * <em>request</em> is only a hint to Google's account chooser and is not a control;
     * the check has to happen here, on the claim that comes back.
     */
    public boolean permits(String email, String hostedDomain)
    {
        if (allowedDomain == null || allowedDomain.isBlank())
            return email != null && !email.isBlank();
        String want = allowedDomain.trim().toLowerCase(Locale.ENGLISH);
        if (hostedDomain != null && want.equalsIgnoreCase(hostedDomain.trim()))
            return true;
        return email != null
            && email.trim().toLowerCase(Locale.ENGLISH).endsWith("@" + want);
    }

    /**
     * Whether this account may edit handicaps and unlock races.
     *
     * <p>An empty {@code admins} list means everyone who can sign in is an admin, which is
     * the honest default for a club where the same two people do everything. Naming
     * anybody makes everybody else a race officer.
     */
    public boolean isAdmin(String email)
    {
        if (admins.isEmpty())
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
