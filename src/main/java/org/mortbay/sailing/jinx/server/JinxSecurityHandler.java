package org.mortbay.sailing.jinx.server;

import org.eclipse.jetty.security.Constraint;
import org.eclipse.jetty.security.SecurityHandler;
import org.eclipse.jetty.server.Request;
import org.mortbay.sailing.jinx.config.AuthConfig;

/**
 * Nothing here demands a login. One path offers one.
 *
 * <p>This used to be {@link Constraint#ANY_USER} for every path, on the reasoning that
 * everything the server holds belongs to the club. The club takes the opposite view of
 * its own results: a season's finishing places are published to be read, by members
 * without accounts and by anyone they send the link to. So every path is
 * {@link Constraint#ALLOWED} and the tiers are enforced where they can be expressed —
 * per operation, in {@code ApiServlet.denyUnless}, rather than per URL here.
 *
 * <p><b>{@link #LOGIN_PATH} is the exception, and it is what makes a login reachable
 * at all.</b> With no constrained path, nothing would ever trigger the OIDC dance and
 * the sign-in button would have nowhere to point. Asking for this one path is asking to
 * sign in; {@code AuthFilter} sends the browser back to the app afterwards.
 *
 * <p>The authenticator's own paths are not listed, because they do not need to be:
 * {@code OpenIdAuthenticator.getConstraintAuthentication} forces its callback to
 * {@code ANY_USER} and its error page to {@code ALLOWED} whatever this returns.
 *
 * <p><b>The loopback exemption is deliberately not here.</b> It is an identity — "treat
 * this request as an administrator" — not an exemption from a constraint, and now that
 * no path has a constraint there is nothing here to exempt it from. It lives in
 * {@link SignedIn}, which is the only place that decides who someone is.
 */
public class JinxSecurityHandler extends SecurityHandler
{
    /** Ask for this and you are asking to sign in. Nothing else is constrained. */
    public static final String LOGIN_PATH = "/auth/login";

    public JinxSecurityHandler(AuthConfig auth)
    {
        // Kept in the signature: the tiers this handler stopped enforcing are still
        // decided from this configuration, one caller along, and a handler that took
        // nothing would invite putting the constraint back.
    }

    @Override
    protected Constraint getConstraint(String pathInContext, Request request)
    {
        return LOGIN_PATH.equals(pathInContext) ? Constraint.ANY_USER : Constraint.ALLOWED;
    }
}
