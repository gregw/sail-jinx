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
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JinxConfigTest
{
    @Test
    void loadFullConfig(@TempDir Path tmp) throws IOException
    {
        Path file = tmp.resolve("config.yaml");
        Files.writeString(file, """
            club:
              domain: "myc.org.au"
              shortName: "MYC"
              longName: "Manly Yacht Club"
              timezone: "Australia/Sydney"
            algorithm:
              penaltyList: [6, 4, 2]
              idealRaceLength: 75         # legacy key — must still load via @JsonAlias
              dnfAllowance: 7
              earliestStart: "17:45"
              latitude: -34.1234
              longitude: 150.9876
              limitBySunset: true
              v0knots: 6.2                # retired key — must be ignored, not fatal
            server:
              port: 9090
              forwardedHeaders: true
            """);

        JinxConfig config = JinxConfig.load(file);

        assertThat(config.club().domain(), equalTo("myc.org.au"));
        assertThat(config.club().shortName(), equalTo("MYC"));
        assertThat(config.club().longName(), equalTo("Manly Yacht Club"));
        assertThat(config.club().timezone(), equalTo("Australia/Sydney"));
        assertThat(config.algorithm().penaltyList(), contains(6.0, 4.0, 2.0));
        assertThat(config.algorithm().defaultRaceDuration(), equalTo(75));
        assertThat(config.algorithm().dnfAllowance(), equalTo(7));
        assertThat(config.algorithm().earliestStart(), equalTo("17:45"));
        assertThat(config.algorithm().latitude(), closeTo(-34.1234, 1e-9));
        assertThat(config.algorithm().longitude(), closeTo(150.9876, 1e-9));
        assertThat(config.algorithm().limitBySunset(), is(true));
        assertThat(config.server().port(), equalTo(9090));
        assertThat(config.server().forwardedHeaders(), is(true));
    }

    @Test
    void fractionalPenaltiesArePreserved(@TempDir Path tmp) throws IOException
    {
        Path file = tmp.resolve("config.yaml");
        Files.writeString(file, """
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
            algorithm: {}
            server: {}
            """);

        JinxConfig config = JinxConfig.load(file);

        assertThat(config.club().timezone(), equalTo("Australia/Sydney"));

        // Algorithm defaults (wiki §10)
        assertThat(config.algorithm().penaltyList(), equalTo(List.of(5.0, 4.0, 3.0, 2.0, 1.0)));
        assertThat(config.algorithm().defaultRaceDuration(), equalTo(90));
        assertThat(config.algorithm().dnfAllowance(), equalTo(1));
        assertThat(config.algorithm().earliestStart(), equalTo("18:00"));
        // Manly Yacht Club ground truth — defaults are tuned to the originating
        // use case; another club overrides via config.yaml.
        assertThat(config.algorithm().latitude(), closeTo(-33.8000, 1e-9));
        assertThat(config.algorithm().longitude(), closeTo(151.2833, 1e-9));
        assertThat(config.algorithm().limitBySunset(), is(false));

        assertThat(config.server().port(), equalTo(8080));
        // Off unless asked for: trusting X-Forwarded-* on a directly-exposed server
        // would let a client decide what address the server thinks it has.
        assertThat(config.server().forwardedHeaders(), is(false));
    }

    @Test
    void anEmptyConfigIsUsable(@TempDir Path tmp) throws IOException
    {
        // A fresh install with a bare config.yaml must start, not crash. Every
        // block is optional and every default is safe.
        Path file = tmp.resolve("config.yaml");
        Files.writeString(file, "{}\n");

        JinxConfig config = JinxConfig.load(file);

        assertThat(config.club().longName(), equalTo("Sailing Club"));
        // shortName falls back to the long one rather than being blank.
        assertThat(config.club().shortName(), equalTo("Sailing Club"));
        assertThat(config.algorithm().defaultRaceDuration(), equalTo(90));
        assertThat(config.server().port(), equalTo(8080));
    }

    @Test
    void aClubWithOnlyANameStillLoads(@TempDir Path tmp) throws IOException
    {
        // "name" was the field before series and race ids became club-scoped. It still
        // maps to longName, so an older config.yaml does not lose the club's identity —
        // but the domain falls back to a placeholder, which is deliberately obvious.
        Path file = tmp.resolve("config.yaml");
        Files.writeString(file, """
            club:
              name: "Manly Yacht Club"
            algorithm: {}
            server: {}
            """);

        JinxConfig config = JinxConfig.load(file);
        assertThat(config.club().longName(), equalTo("Manly Yacht Club"));
        assertThat(config.club().domain(), equalTo("club.invalid"));
    }

    @Test
    void aSailSysEraConfigStillLoads(@TempDir Path tmp) throws IOException
    {
        // Migration safety. A config.yaml from v1 carries a whole sailsys:
        // block — club ids, handicap definition ids, possibly credentials.
        // None of it has a home any more, and none of it should stop the app
        // from starting. The algorithm settings beside it must survive intact.
        Path file = tmp.resolve("config.yaml");
        Files.writeString(file, """
            sailsys:
              email: "legacy@example.com"
              password: "should-be-ignored"
              clubId: 23
              seriesId: 4915
              handicapDefinitionId: 15
              timezone: "Australia/Sydney"
              timezoneOffset: 10
            algorithm:
              penaltyList: [6, 4, 2]
              v0knots: 5.5
            server:
              port: 8080
            """);

        JinxConfig config = JinxConfig.load(file);

        assertThat(config.algorithm().penaltyList(), contains(6.0, 4.0, 2.0));
        assertThat(config.club().timezone(), equalTo("Australia/Sydney"));
        assertThat(config.server().port(), equalTo(8080));
    }

    @Test
    void missingFileThrows(@TempDir Path tmp)
    {
        assertThrows(IOException.class, () -> JinxConfig.load(tmp.resolve("does-not-exist.yaml")));
    }

    @Test
    void theClubsLinksAreConfiguredAndOptional(@TempDir Path tmp) throws IOException
    {
        Path file = tmp.resolve("config.yaml");
        Files.writeString(file, """
            club:
              domain: "myc.org.au"
              longName: "Manly Yacht Club"
              website: "https://www.myc.org.au/"
              otherResults: "https://app.sailsys.com.au/club/23/profile?tab=results"
              seriesEntry: "https://www.myc.org.au/race-series/"
              noticeBoard: "https://www.myc.org.au/notice-board/"
            """);
        JinxConfig.Club club = JinxConfig.load(file).club();
        assertThat(club.website(), equalTo("https://www.myc.org.au/"));
        assertThat(club.otherResults(),
            equalTo("https://app.sailsys.com.au/club/23/profile?tab=results"));
        assertThat(club.seriesEntry(), equalTo("https://www.myc.org.au/race-series/"));
        assertThat(club.noticeBoard(), equalTo("https://www.myc.org.au/notice-board/"));

        // Null, not a guess. A club that has not given a link has no link, and the page
        // leaves the sentence out rather than sending anybody to a URL nobody chose.
        Files.writeString(file, "club:\n  domain: \"myc.org.au\"\n");
        JinxConfig.Club bare = JinxConfig.load(file).club();
        assertThat(bare.website(), is(nullValue()));
        assertThat(bare.otherResults(), is(nullValue()));
        assertThat(bare.seriesEntry(), is(nullValue()));
        assertThat(bare.noticeBoard(), is(nullValue()));
    }
}
