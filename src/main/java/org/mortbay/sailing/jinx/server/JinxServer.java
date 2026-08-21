package org.mortbay.sailing.jinx.server;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.Properties;

import jakarta.servlet.DispatcherType;
import org.eclipse.jetty.ee10.servlet.FilterHolder;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.eclipse.jetty.ee10.servlet.SessionHandler;
import org.eclipse.jetty.security.SecurityHandler;
import org.eclipse.jetty.security.openid.OpenIdAuthenticator;
import org.eclipse.jetty.security.openid.OpenIdConfiguration;
import org.eclipse.jetty.security.openid.OpenIdLoginService;
import org.eclipse.jetty.server.ForwardedRequestCustomizer;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.mortbay.sailing.jinx.config.AuthConfig;
import org.mortbay.sailing.jinx.config.JinxConfig;
import org.mortbay.sailing.jinx.identity.Aliases;
import org.mortbay.sailing.jinx.identity.BoatRegistry;
import org.mortbay.sailing.jinx.identity.DesignCatalogue;
import org.mortbay.sailing.jinx.pursuit.HandicapEngine;
import org.mortbay.sailing.jinx.pursuit.PursuitHandicapEngine;
import org.mortbay.sailing.jinx.store.JsonStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * sail-jinx server entry point. Wires the {@link JsonStore}, the configured
 * {@link HandicapEngine}, and the servlets, then serves them over Jetty.
 *
 * <p>The single argument, when supplied, is the data root; it defaults to
 * {@code ./data}.
 *
 * <p>sail-jinx exchanges no data with anything. The one outbound call it can make
 * is to an OpenID Connect provider, to find out who is asking — see
 * {@link org.mortbay.sailing.jinx.config.AuthConfig}. That is not a data
 * integration, and an HTTP client here for any other purpose is still the
 * regression this architecture exists to prevent.
 *
 * <p>With no {@code auth.yaml} the server has no login, no session handler and no
 * outbound call at all, which is exactly what it did before authentication
 * existed.
 */
public class JinxServer
{
    private static final Logger LOG = LoggerFactory.getLogger(JinxServer.class);

    public static void main(String[] args) throws Exception
    {
        Path dataRoot = (args.length > 0) ? Path.of(args[0]) : Path.of("data");
        Server server = start(dataRoot, -1);

        Runtime.getRuntime().addShutdownHook(new Thread(() ->
        {
            LOG.info("Shutting down");
            try
            {
                server.stop();
            }
            catch (Exception e)
            {
                LOG.error("Error during shutdown", e);
            }
        }));

        server.join();
    }

    /**
     * Build and start a server against the given data root.
     *
     * @param dataRoot directory holding {@code config/} and {@code store/}
     * @param port     port to bind, or a negative value to use the configured
     *                 one. Tests pass {@code 0} for an ephemeral port.
     * @return the started server; the caller owns stopping it
     */
    public static Server start(Path dataRoot, int port) throws Exception
    {
        JinxConfig config = JinxConfig.load(dataRoot.resolve("config/config.yaml"));

        JsonStore store = new JsonStore(dataRoot);
        store.start();

        HandicapEngine engine = new PursuitHandicapEngine(config.algorithm());
        // Identity config lives beside config.yaml and is seeded from sailing-pf, so the
        // two systems agree on what a boat is called.
        Path configDir = dataRoot.resolve("config");
        BoatRegistry registry = new BoatRegistry(store,
            Aliases.load(configDir), DesignCatalogue.load(configDir));
        String version = version();

        Server server = new Server();
        HttpConfiguration http = new HttpConfiguration();
        if (config.server().forwardedHeaders())
        {
            // Behind a proxy the request's own host and scheme are the proxy's. Without
            // this the OAuth redirect_uri comes out as http://localhost:8080/... and
            // Google refuses it — see JinxConfig.Server.
            http.addCustomizer(new ForwardedRequestCustomizer());
            LOG.info("Trusting X-Forwarded-* headers — only correct behind a proxy");
        }
        ServerConnector connector =
            new ServerConnector(server, new HttpConnectionFactory(http));
        connector.setPort(port >= 0 ? port : config.server().port());
        server.addConnector(connector);

        AuthConfig auth = AuthConfig.load(configDir);

        ServletContextHandler context = new ServletContextHandler("/");
        if (auth.enabled())
            secure(context, auth, server);
        context.addServlet(new ServletHolder(
            new ApiServlet(config, auth, store, engine, registry, version)), "/api/*");
        context.addServlet(new ServletHolder(new StaticResourceServlet()), "/*");
        server.setHandler(context);
        server.start();

        LOG.info("sail-jinx {} started on http://localhost:{}/ — data root {}",
            version, connector.getLocalPort(), dataRoot.toAbsolutePath());
        if (!store.loadErrors().isEmpty())
        {
            LOG.error("{} store file(s) could not be read — see errors above",
                store.loadErrors().size());
        }
        return server;
    }

    /**
     * Put the context behind an OpenID Connect login.
     *
     * <p>The issuer is all that is configured: everything else — the authorisation and
     * token endpoints, the signing keys — is discovered from it at startup. That is the
     * one outbound call, and it is why a server with authentication on needs the network
     * to start.
     *
     * <p>Sessions come with it. They are in memory and go when the process does, so a
     * restart mid-race-night signs everybody out; the race data is on disk and unaffected,
     * and the browser's own unsaved-edit recovery still applies.
     */
    private static void secure(ServletContextHandler context, AuthConfig auth, Server server)
    {
        OpenIdConfiguration oidc =
            new OpenIdConfiguration(auth.issuer(), auth.clientId(), auth.clientSecret());
        // Google returns the address and the Workspace domain in these; without them
        // there is nothing to check the club domain against. "openid" is not listed:
        // OpenIdConfiguration already asks for it, and naming it again puts it in the
        // request twice.
        oidc.addScopes("email", "profile");
        // The identity provider and its HTTP client are the server's to start and stop.
        server.addBean(oidc);

        OpenIdAuthenticator authenticator =
            new OpenIdAuthenticator(oidc, auth.redirectPath(), null, "/auth/error");
        SecurityHandler security = new JinxSecurityHandler(auth);
        security.setAuthenticator(authenticator);
        security.setLoginService(new OpenIdLoginService(oidc));

        context.setSessionHandler(new SessionHandler());
        context.setSecurityHandler(security);
        // After the security handler, so the login has happened and there are claims to
        // check. This is what keeps non-club Google accounts out.
        context.addFilter(new FilterHolder(new AuthFilter(auth)), "/*",
            EnumSet.of(DispatcherType.REQUEST));

        LOG.info("Authentication: {} via {}, redirect {}",
            auth.allowedDomain() == null ? "any account" : auth.allowedDomain() + " accounts",
            auth.issuer(), auth.redirectPath());
    }

    /** Build version, from the Maven-filtered {@code jinx.properties}. */
    static String version()
    {
        try (InputStream in = JinxServer.class.getResourceAsStream("/jinx.properties"))
        {
            if (in == null)
                return "unknown";
            Properties props = new Properties();
            props.load(in);
            return props.getProperty("version", "unknown");
        }
        catch (IOException e)
        {
            return "unknown";
        }
    }
}
