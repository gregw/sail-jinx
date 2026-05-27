package org.mortbay.sailing.jinx.sailsys;

import java.time.Instant;

/**
 * Server-side session record produced by a successful SailSys login.
 *
 * <p>This is the only trace of an authenticated user that lives between
 * requests. It carries the opaque SailSys session token (used in the
 * {@code sessiontoken} header on every subsequent API call) and the resolved
 * user identity so the UI can display "signed in as …".
 *
 * <p>It deliberately carries neither the email nor the password used to log in.
 * Those exist only as method parameters during the login call and are eligible
 * for garbage collection as soon as that call returns.
 */
public record SailSysSession(
    String token,
    SailSysClient.User user,
    Instant loginTime)
{
}
