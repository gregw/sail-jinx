package org.mortbay.sailing.jinx.server;

import java.io.IOException;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.security.openid.OpenIdAuthenticator;
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
 *
 * <p>It also serves the two pages that must render for someone who is <em>not</em> signed
 * in — {@link #ERROR_PATH} and sign-out — for the same reason: both exist to get a person
 * out of a state the login put them in, so neither can require a login.
 */
public class AuthFilter implements Filter
{
    private static final Logger LOG = LoggerFactory.getLogger(AuthFilter.class);

    /**
     * Where Jetty sends a failed sign-in, and what {@code JinxServer} hands the
     * authenticator as its error page.
     *
     * <p>Configuring one is not cosmetic. With no error page Jetty answers a failed
     * callback with a bare 403, and every way the OIDC dance can fail looks the same from
     * the browser — which is a long afternoon for whoever is registering the club's OAuth
     * client. Jetty puts the reason in the query, so this renders it.
     */
    public static final String ERROR_PATH = "/auth/error";

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

        // A failed sign-in arrives here unauthenticated by definition — the security
        // handler lets the error path through precisely so this can answer it.
        if (req.getRequestURI().endsWith(ERROR_PATH))
        {
            signInFailed(req, resp);
            return;
        }

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

    /**
     * Explain a failed sign-in.
     *
     * <p>Jetty passes the reason as query parameters: {@code error} and
     * {@code error_description} when the provider said what was wrong, and
     * {@code error_description_jetty} when the check was ours — a state parameter nobody
     * issued, a session that did not survive a restart. It is logged as well as shown,
     * because the person who can fix it is usually reading {@code journalctl} on the Pi
     * rather than looking at the browser that failed.
     */
    private void signInFailed(HttpServletRequest req, HttpServletResponse resp)
        throws IOException
    {
        String reason = firstOf(req.getParameter(OpenIdAuthenticator.ERROR_PARAMETER),
            req.getParameter("error_description"), req.getParameter("error"));
        if (reason == null)
            reason = "no reason given";
        LOG.warn("Sign-in failed: {}", reason);

        resp.setStatus(HttpServletResponse.SC_FORBIDDEN);
        resp.setContentType("text/html; charset=utf-8");
        resp.getWriter().write("""
            <!DOCTYPE html><html lang="en"><head><meta charset="utf-8">
            <title>sail-jinx — sign-in failed</title>
            <style>body{font:16px/1.5 system-ui,sans-serif;margin:4rem auto;max-width:34rem;
            padding:0 1rem;color:#243}a{color:#2f6fb3}code{background:#eef2f5;padding:.1em .3em;
            border-radius:3px}</style></head><body>
            <h1>Sign-in failed</h1>
            <p><code>%s</code></p>
            <p><a href="%s">Try again.</a> If it keeps failing, the reason above is the
            thing to fix — it is in the server log too.</p>
            </body></html>
            """.formatted(esc(reason), esc(req.getContextPath() + "/")));
    }

    private static String firstOf(String... values)
    {
        for (String v : values)
        {
            if (v != null && !v.isBlank())
                return v;
        }
        return null;
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
