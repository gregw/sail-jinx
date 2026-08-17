package org.mortbay.sailing.jinx.server;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Properties;

import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.mortbay.sailing.jinx.config.JinxConfig;
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
 * <p>There is no HTTP <em>client</em> here, and no session handler. Since v2
 * sail-jinx makes no outbound network calls at all and has no login — the
 * process talks to the local filesystem and to the browser in front of it, and
 * to nothing else. If this ever starts up and tries to reach the network,
 * something has gone wrong.
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
        String version = version();

        Server server = new Server();
        ServerConnector connector = new ServerConnector(server);
        connector.setPort(port >= 0 ? port : config.server().port());
        server.addConnector(connector);

        ServletContextHandler context = new ServletContextHandler("/");
        context.addServlet(new ServletHolder(new ApiServlet(config, store, engine, version)), "/api/*");
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
