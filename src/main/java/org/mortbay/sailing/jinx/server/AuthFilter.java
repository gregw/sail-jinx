package org.mortbay.sailing.jinx.server;

import java.io.IOException;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.mortbay.sailing.jinx.config.AuthConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Keeps everyone who is not in the club's Workspace domain out.
 *
 * <p>This is the check that actually restricts access, and it has to exist. Jetty's
 * OpenID authenticator establishes that Google knows who you are — <em>any</em> Google
 * account, including a personal Gmail one. Without this filter, "sign in with Google"
 * would mean "sign in with anything".
 *
 * <p>It runs after the security handler, so by the time it sees a request the login has
 * already happened and the claims are on the session. A rejected account gets a plain 403
 * and a way to sign out, because the usual cause is an RO with two Google accounts whose
 * browser picked the wrong one — an error page that leaves no way back is a support call.
 */
public class AuthFilter implements Filter
{
    private static final Logger LOG = LoggerFactory.getLogger(AuthFilter.class);

    private final AuthConfig auth;

    public AuthFilter(AuthConfig auth)
    {
        this.auth = auth;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
        throws IOException, ServletException
    {
        if (!auth.enabled())
        {
            chain.doFilter(request, response);
            return;
        }

        HttpServletRequest req = (HttpServletRequest)request;
        HttpServletResponse resp = (HttpServletResponse)response;

        // Signing out has to work even for an account that is not allowed in, or the
        // wrong-account case has no exit but clearing cookies by hand.
        if (req.getRequestURI().endsWith("/auth/logout"))
        {
            if (req.getSession(false) != null)
                req.getSession(false).invalidate();
            resp.sendRedirect(req.getContextPath() + "/");
            return;
        }

        SignedIn who = SignedIn.of(req, auth);
        if (who.isSignedIn() && !auth.permits(who.email(), who.domain()))
        {
            LOG.warn("Refused {} — not a {} account", who.email(), auth.allowedDomain());
            deny(req, resp, who.email());
            return;
        }
        chain.doFilter(request, response);
    }

    private void deny(HttpServletRequest req, HttpServletResponse resp, String email)
        throws IOException
    {
        resp.setStatus(HttpServletResponse.SC_FORBIDDEN);
        resp.setContentType("text/html; charset=utf-8");
        String logout = req.getContextPath() + "/auth/logout";
        resp.getWriter().write("""
            <!DOCTYPE html><html lang="en"><head><meta charset="utf-8">
            <title>sail-jinx — wrong account</title>
            <style>body{font:16px/1.5 system-ui,sans-serif;margin:4rem auto;max-width:34rem;
            padding:0 1rem;color:#243}a{color:#2f6fb3}</style></head><body>
            <h1>Not a club account</h1>
            <p>You are signed in as <strong>%s</strong>, which is not a
            <strong>%s</strong> account. sail-jinx only admits club addresses.</p>
            <p>If you have more than one Google account, the browser may have picked the
            wrong one. <a href="%s">Sign out and try again.</a></p>
            </body></html>
            """.formatted(esc(email), esc(auth.allowedDomain()), esc(logout)));
    }

    private static String esc(String s)
    {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;")
            .replace(">", "&gt;").replace("\"", "&quot;");
    }
}
