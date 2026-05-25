package org.mortbay.sailing.jinx.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JinxConfigTest
{
    @Test
    void loadFullConfig(@TempDir Path tmp) throws IOException
    {
        Path file = tmp.resolve("config.yaml");
        Files.writeString(file, """
            sailsys:
              email: "ro@example.com"
              password: "secret"
              clubId: 23
              seriesId: 4915
              handicapDefinitionId: 5
              timezone: "Australia/Sydney"
              timezoneOffset: 11
            algorithm:
              penaltyList: [6, 4, 2]
              idealRaceLength: 75
              dnfAllowance: 7
              earliestStart: "17:45"
            server:
              port: 9090
            """);

        JinxConfig config = JinxConfig.load(file);

        assertThat(config.sailsys().email(), equalTo("ro@example.com"));
        assertThat(config.sailsys().clubId(), equalTo(23));
        assertThat(config.sailsys().seriesId(), equalTo(4915));
        assertThat(config.sailsys().timezoneOffset(), equalTo(11));
        assertThat(config.algorithm().penaltyList(), contains(6, 4, 2));
        assertThat(config.algorithm().idealRaceLength(), equalTo(75));
        assertThat(config.algorithm().dnfAllowance(), equalTo(7));
        assertThat(config.algorithm().earliestStart(), equalTo("17:45"));
        assertThat(config.server().port(), equalTo(9090));
    }

    @Test
    void defaultsAppliedWhenOptionalFieldsOmitted(@TempDir Path tmp) throws IOException
    {
        Path file = tmp.resolve("config.yaml");
        Files.writeString(file, """
            sailsys:
              email: "ro@example.com"
              password: "secret"
              clubId: 23
              seriesId: 4915
            algorithm: {}
            server: {}
            """);

        JinxConfig config = JinxConfig.load(file);

        // SailSys defaults
        assertThat(config.sailsys().handicapDefinitionId(), equalTo(5));      // PHS
        assertThat(config.sailsys().timezone(), equalTo("Australia/Sydney"));
        assertThat(config.sailsys().timezoneOffset(), equalTo(10));

        // Algorithm defaults (from wiki §10)
        assertThat(config.algorithm().penaltyList(), equalTo(List.of(5, 4, 3, 2, 1)));
        assertThat(config.algorithm().idealRaceLength(), equalTo(90));
        assertThat(config.algorithm().dnfAllowance(), equalTo(5));
        assertThat(config.algorithm().earliestStart(), equalTo("18:00"));

        // Server defaults
        assertThat(config.server().port(), equalTo(8080));
    }

    @Test
    void missingFileThrows(@TempDir Path tmp)
    {
        assertThrows(IOException.class, () -> JinxConfig.load(tmp.resolve("does-not-exist.yaml")));
    }
}
