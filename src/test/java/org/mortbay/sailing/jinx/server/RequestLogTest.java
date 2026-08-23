package org.mortbay.sailing.jinx.server;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import org.eclipse.jetty.logging.JettyLogger;
import org.eclipse.jetty.logging.JettyLoggerFactory;
import org.eclipse.jetty.logging.StdErrAppender;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;

/**
 * What the server says about the requests it served.
 *
 * <p>The log goes to the journal rather than to a file, so these tests capture it where
 * it actually comes out — the appender the rest of the server logs through — rather than
 * asserting on the wiring and hoping.
 */
class RequestLogTest
{
    private Server server;
    private final HttpClient http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5)).build();

    @AfterEach
    void stop() throws Exception
    {
        if (server != null)
            server.stop();
    }

    private void startWith(Path tmp, String serverBlock) throws Exception
    {
        Files.createDirectories(tmp.resolve("config"));
        Files.writeString(tmp.resolve("config/config.yaml"), """
            club:
              domain: "test.org.au"
            server:
            %s""".formatted(serverBlock));
        server = JinxServer.start(tmp, 0);
    }

    /** Everything the server logs, for as long as the block runs. */
    private String captureLog(ThrowingRunnable block) throws Exception
    {
        JettyLoggerFactory factory = (JettyLoggerFactory)LoggerFactory.getILoggerFactory();
        JettyLogger logger = factory.getJettyLogger(JinxServer.REQUEST_LOG_NAME);
        StdErrAppender appender = (StdErrAppender)logger.getAppender();
        PrintStream original = appender.getStream();
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        appender.setStream(new PrintStream(captured, true, StandardCharsets.UTF_8));
        try
        {
            block.run();
            // The line is written when the response completes, which can be a moment
            // after the client has finished reading it.
            long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
            while (captured.size() == 0 && System.nanoTime() < deadline)
                Thread.onSpinWait();
            return captured.toString(StandardCharsets.UTF_8);
        }
        finally
        {
            appender.setStream(original);
        }
    }

    private void get(String path) throws Exception
    {
        int port = ((ServerConnector)server.getConnectors()[0]).getLocalPort();
        HttpResponse<String> resp = http.send(HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path)).GET().build(),
            HttpResponse.BodyHandlers.ofString());
        assertThat(resp.statusCode(), is(200));
    }

    @Test
    void everyRequestIsLoggedWithWhatItAskedForAndWhatItGot(@TempDir Path tmp) throws Exception
    {
        startWith(tmp, "  port: 0\n");

        String log = captureLog(() -> get("/api/boats"));

        // The request line and the status: the two things that were missing while the
        // OpenID callback was failing, when the journal showed the login attempt but
        // never what the browser had actually asked for or been told.
        assertThat(log, containsString("\"GET /api/boats HTTP/1.1\" 200"));
    }

    @Test
    void theLogCanBeTurnedOff(@TempDir Path tmp) throws Exception
    {
        startWith(tmp, "  port: 0\n  requestLog: false\n");

        assertThat(server.getRequestLog(), is(nullValue()));
        assertThat(captureLog(() -> get("/api/boats")), not(containsString("/api/boats")));
    }

    @FunctionalInterface
    private interface ThrowingRunnable
    {
        void run() throws Exception;
    }
}
