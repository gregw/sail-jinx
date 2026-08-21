package org.mortbay.sailing.jinx.server;

import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.eclipse.jetty.security.openid.OpenIdAuthenticator;
import org.mortbay.sailing.jinx.config.AuthConfig;

/**
 * Who is making this request, as the rest of the server cares about it.
 *
 * <p>The claims come from the session, where {@code OpenIdAuthenticator} leaves them
 * after a successful login. Reading them there rather than re-parsing the id token keeps
 * this to a map lookup and means the token is verified exactly once, by Jetty.
 *
 * @param email  the club address, lower-cased, or null when nobody is signed in
 * @param name   the display name Google supplies, or null
 * @param admin  whether this account may edit handicaps and unlock races
 * @param domain the {@code hd} claim — the Workspace domain Google asserts
 */
public record SignedIn(String email, String name, boolean admin, String domain)
{
    /** Nobody is signed in, and authentication is off, so everybody is an admin. */
    public static final SignedIn ANONYMOUS_ADMIN = new SignedIn(null, null, true, null);

    /** Nobody is signed in and authentication is on. */
    public static final SignedIn NOBODY = new SignedIn(null, null, false, null);

    public boolean isSignedIn()
    {
        return email != null;
    }

    /**
     * Read the signed-in account off the request.
     *
     * <p>With authentication off, or on a loopback request that the security handler let
     * through unauthenticated, this is {@link #ANONYMOUS_ADMIN} — the behaviour sail-jinx
     * had before there was a login at all.
     */
    public static SignedIn of(HttpServletRequest req, AuthConfig auth)
    {
        if (auth == null || !auth.enabled())
            return ANONYMOUS_ADMIN;

        Map<String, Object> claims = claims(req);
        if (claims == null)
        {
            // Authenticated by the container but with no claims to read means the
            // loopback exemption let this through; anything else is not signed in.
            return req.getUserPrincipal() == null && !isLoopback(req)
                ? NOBODY : ANONYMOUS_ADMIN;
        }
        String email = str(claims.get("email"));
        String lower = email == null ? null : email.toLowerCase(java.util.Locale.ENGLISH);
        return new SignedIn(lower, str(claims.get("name")),
            auth.isAdmin(lower), str(claims.get("hd")));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> claims(HttpServletRequest req)
    {
        HttpSession session = req.getSession(false);
        if (session == null)
            return null;
        Object claims = session.getAttribute(OpenIdAuthenticator.CLAIMS);
        return claims instanceof Map ? (Map<String, Object>)claims : null;
    }

    private static boolean isLoopback(HttpServletRequest req)
    {
        String remote = req.getRemoteAddr();
        return "127.0.0.1".equals(remote) || "::1".equals(remote)
            || "0:0:0:0:0:0:0:1".equals(remote);
    }

    private static String str(Object o)
    {
        return o == null ? null : String.valueOf(o);
    }
}
