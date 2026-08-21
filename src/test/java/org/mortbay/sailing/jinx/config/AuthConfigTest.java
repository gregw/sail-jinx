package org.mortbay.sailing.jinx.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AuthConfigTest
{
    private static AuthConfig write(Path dir, String yaml) throws IOException
    {
        Files.writeString(dir.resolve("auth.yaml"), yaml);
        return AuthConfig.load(dir);
    }

    @Test
    void noFileMeansAuthenticationIsOff(@TempDir Path dir) throws IOException
    {
        AuthConfig a = AuthConfig.load(dir);
        assertThat(a.enabled(), is(false));
        // The machine on the office desk keeps working exactly as it did.
        assertThat(a.isAdmin(null), is(true));
    }

    @Test
    void aBrokenFileStopsTheServerRatherThanFailingOpen(@TempDir Path dir) throws IOException
    {
        // Half-configured is the dangerous state: a server that starts anyway is a
        // server nobody notices is unprotected.
        assertThrows(IllegalStateException.class, () ->
            write(dir, "enabled: true\nclientId: \"abc\"\n"));
        assertThrows(IllegalStateException.class, () ->
            write(dir, "enabled: true\nclientSecret: \"shh\"\n"));
        assertThrows(Exception.class, () ->
            write(dir, "enabled: true\n  this is not: [valid\n"));
    }

    @Test
    void aFullFileLoads(@TempDir Path dir) throws IOException
    {
        AuthConfig a = write(dir, """
            enabled: true
            clientId: "1234.apps.googleusercontent.com"
            clientSecret: "shh"
            allowedDomain: "myc.org.au"
            admins:
              - "Commodore@MYC.org.au"
              - "  "
            allowLoopback: true
            """);
        assertThat(a.enabled(), is(true));
        assertThat(a.issuer(), equalTo(AuthConfig.GOOGLE));
        assertThat(a.redirectPath(), equalTo("/auth/callback"));
        // Addresses are case-insensitive, and blanks are not people.
        assertThat(a.admins(), contains("commodore@myc.org.au"));
        assertThat(a.allowLoopback(), is(true));
    }

    @Test
    void aRedirectPathIsAlwaysRooted(@TempDir Path dir) throws IOException
    {
        AuthConfig a = write(dir, """
            enabled: true
            clientId: "x"
            clientSecret: "y"
            redirectPath: "oidc/back"
            """);
        assertThat(a.redirectPath(), equalTo("/oidc/back"));
    }

    @Test
    void onlyTheClubDomainGetsIn()
    {
        AuthConfig a = new AuthConfig(true, null, "id", "secret", null,
            "myc.org.au", java.util.List.of(), false);

        // The hd claim is what Google asserts about a Workspace account.
        assertThat(a.permits("skipper@myc.org.au", "myc.org.au"), is(true));
        // …and the address alone will do when hd is absent.
        assertThat(a.permits("skipper@myc.org.au", null), is(true));
        assertThat(a.permits("Skipper@MYC.ORG.AU", null), is(true));

        // A personal Google account is exactly what this keeps out.
        assertThat(a.permits("someone@gmail.com", null), is(false));
        assertThat(a.permits("someone@gmail.com", ""), is(false));
        // And a lookalike domain must not squeak through on a suffix match.
        assertThat(a.permits("someone@notmyc.org.au", null), is(false));
        assertThat(a.permits("someone@myc.org.au.evil.com", null), is(false));
        assertThat(a.permits(null, null), is(false));
    }

    @Test
    void namingNoAdminsMakesEveryoneOne()
    {
        AuthConfig open = new AuthConfig(true, null, "id", "secret", null,
            "myc.org.au", java.util.List.of(), false);
        assertThat(open.isAdmin("anyone@myc.org.au"), is(true));

        AuthConfig named = new AuthConfig(true, null, "id", "secret", null,
            "myc.org.au", java.util.List.of("commodore@myc.org.au"), false);
        assertThat(named.isAdmin("commodore@myc.org.au"), is(true));
        assertThat(named.isAdmin("COMMODORE@myc.org.au"), is(true));
        // Everyone else can still run a race night, just not touch handicaps.
        assertThat(named.isAdmin("crew@myc.org.au"), is(false));
        assertThat(named.isAdmin(null), is(false));
    }
}
