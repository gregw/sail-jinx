package org.mortbay.sailing.jinx.server;

import java.nio.file.Path;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.transport.HttpClientTransportDynamic;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.eclipse.jetty.ee10.servlet.SessionHandler;
import org.eclipse.jetty.io.ClientConnector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.mortbay.sailing.jinx.config.JinxConfig;
import org.mortbay.sailing.jinx.pursuit.HandicapEngine;
import org.mortbay.sailing.jinx.pursuit.PursuitHandicapEngine;
import org.mortbay.sailing.jinx.sailsys.SailSysClient;
import org.mortbay.sailing.jinx.store.JsonStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * sail-jinx server entry point. Wires the JsonStore, SailSysClient, the
 * configured HandicapEngine, and exposes them via Jetty.
 *
 * <p>The single arg, when supplied, is the data root. Defaults to {@code ./data}
 * in the working directory.
 */
public class JinxServer
{
    private static final Logger LOG = LoggerFactory.getLogger(JinxServer.class);

    public static void main(String[] args) throws Exception
    {
        Path dataRoot = (args.length > 0) ? Path.of(args[0]) : Path.of("data");
        Path configFile = dataRoot.resolve("config/config.yaml");
        JinxConfig config = JinxConfig.load(configFile);

        JsonStore store = new JsonStore(dataRoot);
        store.start();

        SslContextFactory.Client ssl = new SslContextFactory.Client();
        ClientConnector clientConnector = new ClientConnector();
        clientConnector.setSslContextFactory(ssl);
        HttpClient httpClient = new HttpClient(new HttpClientTransportDynamic(clientConnector));
        httpClient.start();

        SailSysClient sailsys = new SailSysClient(httpClient, config.sailsys());
        HandicapEngine engine = new PursuitHandicapEngine(config.algorithm());

        Server server = new Server();
        ServerConnector connector = new ServerConnector(server);
        connector.setPort(config.server().port());
        server.addConnector(connector);

        ServletContextHandler context = new ServletContextHandler("/");

        // HttpSession is how we associate the SailSys session token with a
        // particular browser. Lax SameSite is fine — this app is single-origin
        // and not embedded; we just need the session cookie to follow normal
        // top-level navigations.
        SessionHandler sessionHandler = new SessionHandler();
        sessionHandler.getSessionCookieConfig().setAttribute("SameSite", "Lax");
        sessionHandler.getSessionCookieConfig().setHttpOnly(true);
        context.setSessionHandler(sessionHandler);

        context.addServlet(new ServletHolder(new ApiServlet(config, store, sailsys, engine)), "/api/*");
        context.addServlet(new ServletHolder(new StaticResourceServlet()), "/*");
        server.setHandler(context);
        server.start();

        Runtime.getRuntime().addShutdownHook(new Thread(() ->
        {
            LOG.info("Shutting down");
            try
            {
                server.stop();
                httpClient.stop();
            }
            catch (Exception e)
            {
                LOG.error("Error during shutdown", e);
            }
        }));

        LOG.info("sail-jinx started on http://localhost:{}/", config.server().port());
        server.join();
    }
}
