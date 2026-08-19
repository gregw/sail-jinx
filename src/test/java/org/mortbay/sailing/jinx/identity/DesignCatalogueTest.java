package org.mortbay.sailing.jinx.identity;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

class DesignCatalogueTest
{
    private static DesignCatalogue withYaml(Path dir, String yaml) throws Exception
    {
        Files.writeString(dir.resolve("design.yaml"), yaml);
        return DesignCatalogue.load(dir);
    }

    @Test
    void genericLabelsAreIgnored(@TempDir Path dir) throws Exception
    {
        DesignCatalogue c = withYaml(dir, """
            ignored:
            - "yacht"
            - "sloop"
            - "cruiser racer"
            """);

        assertThat(c.isIgnored("yacht"), is(true));
        assertThat(c.isIgnored("sloop"), is(true));
        // The list is normalised on load, so a spaced entry matches a normalised query.
        assertThat(c.isIgnored("cruiserracer"), is(true));
        assertThat(c.isIgnored("j24"), is(false));
        assertThat(c.isIgnored(null), is(false));
    }

    @Test
    void outOfScopeClassesAreExcluded(@TempDir Path dir) throws Exception
    {
        DesignCatalogue c = withYaml(dir, """
            excluded:
            - "laser"
            - "16ft skiff"
            """);
        assertThat(c.isExcluded("laser"), is(true));
        assertThat(c.isExcluded("16ftskiff"), is(true));
        assertThat(c.isExcluded("farr40"), is(false));
    }

    @Test
    void designsThatCannotFlyASpinnakerAreFlagged(@TempDir Path dir) throws Exception
    {
        DesignCatalogue c = withYaml(dir, """
            noSpinnaker:
            - "radford12catrig"
            """);
        assertThat(c.isNoSpinnaker("radford12catrig"), is(true));
        assertThat(c.isNoSpinnaker("j24"), is(false));
    }

    @Test
    void aBoatCanBeOverriddenToItsRealDesign(@TempDir Path dir) throws Exception
    {
        DesignCatalogue c = withYaml(dir, """
            boatDesignOverrides:
            - designId: sydney36mkii
              canonicalName: "Sydney 36 MkII"
              boats:
              - sailNumber: "5915"
                name: "Stormaway"
            """);

        assertThat(c.resolveOverride("5915", "stormaway", null), equalTo("sydney36mkii"));
        assertThat(c.overrideDesignName("sydney36mkii"), equalTo("Sydney 36 MkII"));
        assertThat(c.resolveOverride("5915", "someoneelse", null), nullValue());
        assertThat(c.resolveOverride("9999", "stormaway", null), nullValue());
    }

    @Test
    void aDatedOverrideOnlyAppliesWithinItsWindow(@TempDir Path dir) throws Exception
    {
        // Boats get refitted. Races before the refit must keep scoring against the old
        // design, so the override is bounded rather than retrospective.
        DesignCatalogue c = withYaml(dir, """
            boatDesignOverrides:
            - designId: farr40
              boats:
              - sailNumber: "1234"
                name: "Refit"
                from: 2026-01-01
                until: 2026-12-31
            """);

        assertThat(c.resolveOverride("1234", "refit", LocalDate.of(2026, 6, 5)), equalTo("farr40"));
        assertThat(c.resolveOverride("1234", "refit", LocalDate.of(2025, 12, 31)), nullValue());
        assertThat(c.resolveOverride("1234", "refit", LocalDate.of(2027, 1, 1)), nullValue());
        // Boundaries are inclusive.
        assertThat(c.resolveOverride("1234", "refit", LocalDate.of(2026, 1, 1)), equalTo("farr40"));
        assertThat(c.resolveOverride("1234", "refit", LocalDate.of(2026, 12, 31)), equalTo("farr40"));
    }

    @Test
    void anUndatedQueryOnlyMatchesAnUndatedOverride(@TempDir Path dir) throws Exception
    {
        // Without a date there is no honest answer to "which design was it at the time",
        // so a windowed override is not applied by default.
        DesignCatalogue c = withYaml(dir, """
            boatDesignOverrides:
            - designId: farr40
              boats:
              - sailNumber: "1234"
                name: "Refit"
                from: 2026-01-01
            """);
        assertThat(c.resolveOverride("1234", "refit", null), nullValue());
    }

    @Test
    void anOpenEndedOverrideRunsForever(@TempDir Path dir) throws Exception
    {
        DesignCatalogue c = withYaml(dir, """
            boatDesignOverrides:
            - designId: farr40
              boats:
              - sailNumber: "1234"
                name: "Refit"
                from: 2026-01-01
            """);
        assertThat(c.resolveOverride("1234", "refit", LocalDate.of(2099, 1, 1)), equalTo("farr40"));
    }

    @Test
    void aMissingOrBrokenFileIsNotAnError(@TempDir Path dir) throws Exception
    {
        DesignCatalogue missing = DesignCatalogue.load(dir);
        assertThat(missing.isIgnored("yacht"), is(false));

        // A broken file must not stop the server; every design is simply taken at face value.
        Files.writeString(dir.resolve("design.yaml"), "ignored: [unclosed\n");
        DesignCatalogue broken = DesignCatalogue.load(dir);
        assertThat(broken.isIgnored("yacht"), is(false));
    }

    @Test
    void theShippedSeedLoadsAndAnswers()
    {
        // Guards the seed copied from sailing-pf: a malformed entry should fail here
        // rather than silently disabling every judgement in the file.
        DesignCatalogue c = DesignCatalogue.load(Path.of("data/config"));

        assertThat(c.isIgnored("yacht"), is(true));
        assertThat(c.isIgnored("sloop"), is(true));
        assertThat(c.isExcluded("laser"), is(true));
        assertThat(c.isNoSpinnaker("radford12catrig"), is(true));
        assertThat(c.resolveOverride("MYC12", "santoy", null), equalTo("radford12catrig"));
        assertThat(c.resolveOverride("5915", "stormaway", null), equalTo("sydney36mkii"));
        // A real design is neither ignored nor excluded.
        assertThat(c.isIgnored("beneteaufirst407"), is(false));
        assertThat(c.isExcluded("beneteaufirst407"), is(false));
    }
}
