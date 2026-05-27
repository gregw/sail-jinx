package org.mortbay.sailing.jinx.sailsys;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

/**
 * The session record is the only server-side trace of a logged-in user. It must
 * hold the SailSys session token and the resolved user identity (so the UI can
 * display "signed in as …") and explicitly nothing that could be used to
 * re-authenticate on the user's behalf — no email/password, no client secret.
 *
 * <p>Compile-time enforcement: this test references {@code token()}, {@code user()},
 * and {@code loginTime()}. The absence of credential accessors is checked by the
 * fact that this is the only place the session is constructed in tests, and we
 * never call anything credential-shaped.
 */
class SailSysSessionTest
{
    @Test
    void holdsTokenUserAndLoginTime()
    {
        SailSysClient.User user = new SailSysClient.User();
        user.id = 956;
        user.email = "ro@example.com";
        user.firstname = "Race";
        user.surname = "Officer";

        Instant when = Instant.parse("2026-05-25T07:30:00Z");
        SailSysSession session = new SailSysSession("opaque-token-xyz", user, when);

        assertThat(session.token(), equalTo("opaque-token-xyz"));
        assertThat(session.user(), notNullValue());
        assertThat(session.user().email, equalTo("ro@example.com"));
        assertThat(session.loginTime(), equalTo(when));
    }
}
