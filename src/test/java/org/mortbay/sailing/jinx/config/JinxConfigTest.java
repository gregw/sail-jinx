package org.mortbay.sailing.jinx.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.closeTo;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JinxConfigTest
{
    @Test
    void loadFullConfig(@TempDir Path tmp) throws IOException
    {
        Path file = tmp.resolve("config.yaml");
        Files.writeString(file, """
            sailsys:
              clubId: 23
              handicapDefinitionId: 5
              timezone: "Australia/Sydney"
              timezoneOffset: 11
            algorithm:
              penaltyList: [6, 4, 2]
              idealRaceLength: 75
              dnfAllowance: 7
              earliestStart: "17:45"
              latitude: -34.1234
              longitude: 150.9876
              limitBySunset: true
            server:
              port: 9090
            """);

        JinxConfig config = JinxConfig.load(file);

        assertThat(config.sailsys().clubId(), equalTo(23));
        assertThat(config.sailsys().timezoneOffset(), equalTo(11));
        assertThat(config.algorithm().penaltyList(), contains(6.0, 4.0, 2.0));
        assertThat(config.algorithm().idealRaceLength(), equalTo(75));
        assertThat(config.algorithm().dnfAllowance(), equalTo(7));
        assertThat(config.algorithm().earliestStart(), equalTo("17:45"));
        assertThat(config.algorithm().latitude(), closeTo(-34.1234, 1e-9));
        assertThat(config.algorithm().longitude(), closeTo(150.9876, 1e-9));
        assertThat(config.algorithm().limitBySunset(), is(true));
        assertThat(config.server().port(), equalTo(9090));
    }

    @Test
    void fractionalPenaltiesArePreserved(@TempDir Path tmp) throws IOException
    {
        Path file = tmp.resolve("config.yaml");
        Files.writeString(file, """
            sailsys:
              clubId: 23
            algorithm:
              penaltyList: [5, 4, 3, 2, 1, 0.5, 0.25]
            server: {}
            """);

        JinxConfig config = JinxConfig.load(file);

        assertThat(config.algorithm().penaltyList(),
            contains(5.0, 4.0, 3.0, 2.0, 1.0, 0.5, 0.25));
    }

    @Test
    void defaultsAppliedWhenOptionalFieldsOmitted(@TempDir Path tmp) throws IOException
    {
        Path file = tmp.resolve("config.yaml");
        Files.writeString(file, """
            sailsys:
              clubId: 23
            algorithm: {}
            server: {}
            """);

        JinxConfig config = JinxConfig.load(file);

        // SailSys defaults
        assertThat(config.sailsys().handicapDefinitionId(), equalTo(5));      // PHS
        assertThat(config.sailsys().timezone(), equalTo("Australia/Sydney"));
        assertThat(config.sailsys().timezoneOffset(), equalTo(10));

        // Algorithm defaults (from wiki §10)
        assertThat(config.algorithm().penaltyList(), equalTo(List.of(5.0, 4.0, 3.0, 2.0, 1.0)));
        assertThat(config.algorithm().idealRaceLength(), equalTo(90));
        assertThat(config.algorithm().dnfAllowance(), equalTo(5));
        assertThat(config.algorithm().earliestStart(), equalTo("18:00"));
        // Manly Yacht Club ground truth — defaults are tuned to the
        // originating use case; another club overrides via config.yaml.
        assertThat(config.algorithm().latitude(), closeTo(-33.8000, 1e-9));
        assertThat(config.algorithm().longitude(), closeTo(151.2833, 1e-9));
        assertThat(config.algorithm().limitBySunset(), is(false));

        // Server defaults
        assertThat(config.server().port(), equalTo(8080));
    }

    @Test
    void legacyFieldsInYamlAreIgnored(@TempDir Path tmp) throws IOException
    {
        // Migration safety: a legacy config.yaml may still carry email, password,
        // or seriesId. None of these are part of the model any more — credentials
        // come from the login form, and the series is a runtime choice driven by
        // the Series tab. Load must succeed and expose no accessor for them
        // (verified at compile time by the absence of any reference to .email() /
        // .password() / .seriesId() in this test or anywhere else).
        Path file = tmp.resolve("config.yaml");
        Files.writeString(file, """
            sailsys:
              email: "legacy@example.com"
              password: "should-be-ignored"
              clubId: 23
              seriesId: 4915
            algorithm: {}
            server: {}
            """);

        JinxConfig config = JinxConfig.load(file);
        assertThat(config.sailsys().clubId(), equalTo(23));
    }

    @Test
    void missingFileThrows(@TempDir Path tmp)
    {
        assertThrows(IOException.class, () -> JinxConfig.load(tmp.resolve("does-not-exist.yaml")));
    }
}
