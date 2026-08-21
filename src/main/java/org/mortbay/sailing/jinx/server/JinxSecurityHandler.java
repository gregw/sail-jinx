package org.mortbay.sailing.jinx.server;

import java.net.InetSocketAddress;
import java.net.SocketAddress;

import org.eclipse.jetty.security.Constraint;
import org.eclipse.jetty.security.SecurityHandler;
import org.eclipse.jetty.server.Request;
import org.mortbay.sailing.jinx.config.AuthConfig;

/**
 * Everything on this server needs a signed-in club account.
 *
 * <p>Not a path-by-path constraint list. There is nothing here a stranger should see: the
 * fleet register, the season's handicaps and the race results are all the club's, and a
 * page that renders before the login would only be a shell with an error in it. So the
 * rule is one line — {@link Constraint#ANY_USER} for every path — and the interesting
 * part is the two exceptions.
 *
 * <p><b>The authenticator's own paths</b> are not exempted here, because they do not need
 * to be: Jetty's {@code OpenIdAuthenticator} intercepts its redirect and error paths
 * before the constraint is ever consulted.
 *
 * <p><b>Loopback</b> is exempt only when {@code allowLoopback} says so, and it is off by
 * default. It exists for the one deployment where it is the right answer — the club's own
 * PC with the browser on the same machine, where the alternative is that a race night
 * cannot be scored during an internet outage. It is the wrong answer everywhere else, and
 * dangerously so behind a reverse proxy, where every request arrives from 127.0.0.1 and
 * this would exempt the entire internet.
 */
public class JinxSecurityHandler extends SecurityHandler
{
    private final AuthConfig auth;

    public JinxSecurityHandler(AuthConfig auth)
    {
        this.auth = auth;
    }

    @Override
    protected Constraint getConstraint(String pathInContext, Request request)
    {
        if (auth.allowLoopback() && isLoopback(request))
            return Constraint.ALLOWED;
        return Constraint.ANY_USER;
    }

    /**
     * True when the connection came from this machine.
     *
     * <p>Deliberately the <em>connection's</em> address, never a forwarded header: an
     * {@code X-Forwarded-For} a client can set is not evidence of anything, and treating
     * it as such would turn the loopback exemption into a bypass anybody could ask for.
     */
    static boolean isLoopback(Request request)
    {
        if (request == null)
            return false;
        SocketAddress remote = request.getConnectionMetaData().getRemoteSocketAddress();
        return remote instanceof InetSocketAddress inet
            && inet.getAddress() != null
            && inet.getAddress().isLoopbackAddress();
    }
}
