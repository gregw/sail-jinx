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

    private static AuthConfig config(java.util.List<String> domains,
                                     java.util.List<String> admins,
                                     java.util.List<String> raceOfficers)
    {
        return new AuthConfig(true, null, "id", "secret", null, domains, admins,
            raceOfficers, false);
    }

    @Test
    void anAccountInAnAllowedDomainIsARaceOfficer()
    {
        AuthConfig a = config(java.util.List.of("myc.org.au"), java.util.List.of(),
            java.util.List.of());

        // The hd claim is what Google asserts about a Workspace account.
        assertThat(a.isRaceOfficer("skipper@myc.org.au", "myc.org.au"), is(true));
        // …and the address alone will do when hd is absent.
        assertThat(a.isRaceOfficer("skipper@myc.org.au", null), is(true));
        assertThat(a.isRaceOfficer("Skipper@MYC.ORG.AU", null), is(true));

        // A personal Google account signs in, but does not run races.
        assertThat(a.isRaceOfficer("someone@gmail.com", null), is(false));
        assertThat(a.isRaceOfficer("someone@gmail.com", ""), is(false));
        // And a lookalike domain must not squeak through on a suffix match.
        assertThat(a.isRaceOfficer("someone@notmyc.org.au", null), is(false));
        assertThat(a.isRaceOfficer("someone@myc.org.au.evil.com", null), is(false));
        assertThat(a.isRaceOfficer(null, null), is(false));
    }

    @Test
    void severalDomainsMayBeAllowedAndTheOldSingleKeyStillLoads(@TempDir Path dir)
        throws IOException
    {
        AuthConfig a = write(dir, """
            enabled: true
            clientId: "x"
            clientSecret: "y"
            allowedDomains:
              - "myc.org.au"
              - "Friends.Example.org"
            """);
        assertThat(a.allowedDomains(), contains("myc.org.au", "friends.example.org"));
        assertThat(a.isRaceOfficer("ro@friends.example.org", null), is(true));
        assertThat(a.isRaceOfficer("ro@myc.org.au", "myc.org.au"), is(true));
        assertThat(a.isRaceOfficer("ro@elsewhere.org", null), is(false));

        // The key every auth.yaml written before this has.
        AuthConfig old = write(dir, """
            enabled: true
            clientId: "x"
            clientSecret: "y"
            allowedDomain: "myc.org.au"
            """);
        assertThat(old.allowedDomains(), contains("myc.org.au"));
        assertThat(old.isRaceOfficer("ro@myc.org.au", null), is(true));
    }

    /**
     * No allowed domain: anyone the issuer authenticates may sign in, but only the
     * accounts named in raceOfficers (or admins) may change anything.
     */
    @Test
    void withNoDomainOnlyTheNamedRaceOfficersAreOnes()
    {
        AuthConfig a = config(java.util.List.of(), java.util.List.of(),
            java.util.List.of("Helper@Gmail.com", " "));
        assertThat(a.raceOfficers(), contains("helper@gmail.com"));
        assertThat(a.isRaceOfficer("helper@gmail.com", null), is(true));
        assertThat(a.isRaceOfficer("HELPER@gmail.com", null), is(true));
        assertThat(a.isRaceOfficer("skipper@myc.org.au", "myc.org.au"), is(false));
        assertThat(a.isRaceOfficer(null, null), is(false));
    }

    /**
     * An empty admins list used to make everyone who signed in an admin. With any Google
     * account able to sign in, that would be an admin account handed to the world.
     */
    @Test
    void namingNoAdminsMakesNobodyOne()
    {
        AuthConfig none = config(java.util.List.of("myc.org.au"), java.util.List.of(),
            java.util.List.of());
        assertThat(none.isAdmin("anyone@myc.org.au"), is(false));
        assertThat(none.isAdmin(null), is(false));

        AuthConfig named = config(java.util.List.of("myc.org.au"),
            java.util.List.of("commodore@myc.org.au"), java.util.List.of());
        assertThat(named.isAdmin("commodore@myc.org.au"), is(true));
        assertThat(named.isAdmin("COMMODORE@myc.org.au"), is(true));
        assertThat(named.isAdmin("crew@myc.org.au"), is(false));
        assertThat(named.isAdmin(null), is(false));
    }
}
