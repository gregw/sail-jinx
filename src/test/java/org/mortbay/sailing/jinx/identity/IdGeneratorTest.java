package org.mortbay.sailing.jinx.identity;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

/**
 * These rules are shared with the sailing-pf project — the same boats, in the same
 * fleet, are entered into both, so a divergence here silently becomes two boats.
 * Treat a failure as a compatibility break, not just a local regression.
 */
class IdGeneratorTest
{
    @Test
    void sailNumbersUppercaseAndLoseTheirPunctuation()
    {
        assertThat(IdGenerator.normaliseSailNumber("AUS-1234"), equalTo("AUS1234"));
        assertThat(IdGenerator.normaliseSailNumber("aus 1234"), equalTo("AUS1234"));
        assertThat(IdGenerator.normaliseSailNumber("myc 7"), equalTo("MYC7"));
        assertThat(IdGenerator.normaliseSailNumber("012345"), equalTo("012345"));
        assertThat(IdGenerator.normaliseSailNumber(null), equalTo(""));
    }

    @Test
    void namesLowercaseAndLoseEverythingElse()
    {
        assertThat(IdGenerator.normaliseName("Raging Bull"), equalTo("ragingbull"));
        assertThat(IdGenerator.normaliseName("TenSixty"), equalTo("tensixty"));
        assertThat(IdGenerator.normaliseName("St. Elmo's Fire"), equalTo("stelmosfire"));
        assertThat(IdGenerator.normaliseName(null), equalTo(""));
    }

    @Test
    void sponsorPrefixesAreKept()
    {
        // Deliberate: only an explicit alias may bridge these. Guessing that
        // "Komatsu Azzuro" is "Azzuro" would merge boats that are genuinely distinct
        // in other fleets.
        assertThat(IdGenerator.normaliseName("Komatsu Azzuro"), equalTo("komatsuazzuro"));
        assertThat(IdGenerator.normaliseName("Azzuro"), equalTo("azzuro"));
    }

    @Test
    void decorativeDivisionSuffixesAreStripped()
    {
        assertThat(IdGenerator.stripStandardSuffixes("Foobar - GM"), equalTo("Foobar"));
        assertThat(IdGenerator.stripStandardSuffixes("Foobar-gm"), equalTo("Foobar"));
        assertThat(IdGenerator.stripStandardSuffixes("Kilifi - Under 17"), equalTo("Kilifi"));
        assertThat(IdGenerator.stripStandardSuffixes("Foobar -UNDER  18"), equalTo("Foobar"));
        // Iterative, so chained markers collapse fully.
        assertThat(IdGenerator.stripStandardSuffixes("Boat - GM - U18"), equalTo("Boat"));
        // A hyphen that is part of the name survives.
        assertThat(IdGenerator.stripStandardSuffixes("Half-Way"), equalTo("Half-Way"));
    }

    @Test
    void aDivisionSuffixDoesNotCreateASecondBoat()
    {
        assertThat(IdGenerator.normaliseName("Foobar - GM"),
            equalTo(IdGenerator.normaliseName("Foobar")));
    }

    @Test
    void designNamesCollapseTheirPunctuation()
    {
        assertThat(IdGenerator.normaliseDesignName("J/24"), equalTo("j24"));
        assertThat(IdGenerator.normaliseDesignName("J 24"), equalTo("j24"));
        assertThat(IdGenerator.normaliseDesignName("Beneteau First 40.7"), equalTo("beneteaufirst407"));
        assertThat(IdGenerator.normaliseDesignName(null), equalTo(""));
    }

    @Test
    void boatIdsCarryTheDesignWhenItIsKnown()
    {
        assertThat(IdGenerator.generateBoatId("AUS1234", "Raging Bull", null),
            equalTo("AUS1234-ragingbull"));
        assertThat(IdGenerator.generateBoatId("AUS1234", "Raging Bull", "j24"),
            equalTo("AUS1234-ragingbull-j24"));
        assertThat(IdGenerator.generateBoatId("AUS1234", "Raging Bull", ""),
            equalTo("AUS1234-ragingbull"));
    }

    @Test
    void aBoatWithNoSailNumberStillGetsAnId()
    {
        assertThat(IdGenerator.generateBoatId(null, "Visitor", null), equalTo("nosail-visitor"));
        assertThat(IdGenerator.generateBoatId("  ", "Visitor", null), equalTo("nosail-visitor"));
    }

    @Test
    void nameMatchKeyCollapsesTheNuisanceVariants()
    {
        assertThat(IdGenerator.nameMatchKey("Goat"), equalTo("goat"));
        assertThat(IdGenerator.nameMatchKey("The Goat"), equalTo("goat"));
        assertThat(IdGenerator.nameMatchKey("Sticky 2"), equalTo("sticky"));
        assertThat(IdGenerator.nameMatchKey("Sticky II"), equalTo("sticky"));
        assertThat(IdGenerator.nameMatchKey("The Sticky 2 - GM"), equalTo("sticky"));
    }

    @Test
    void nameMatchKeyLeavesEmbeddedLetterRunsAlone()
    {
        // The whitespace requirement is what protects these: "li" and "a" only look
        // like Roman numerals.
        assertThat(IdGenerator.nameMatchKey("Tivoli"), equalTo("tivoli"));
        assertThat(IdGenerator.nameMatchKey("Anna"), equalTo("anna"));
        // "the" without a following space is just the start of a word.
        assertThat(IdGenerator.nameMatchKey("Thelma"), equalTo("thelma"));
        assertThat(IdGenerator.nameMatchKey(null), equalTo(""));
        assertThat(IdGenerator.nameMatchKey("  "), equalTo(""));
    }

    @Test
    void preferredDisplayNameKeepsTheLongestBody()
    {
        assertThat(IdGenerator.preferredDisplayName(List.of("Goat", "The Goat")),
            equalTo("The Goat"));
        assertThat(IdGenerator.preferredDisplayName(List.of("Foobar - GM", "Foobar")),
            equalTo("Foobar"));
    }

    @Test
    void preferredDisplayNamePrefersArabicNumerals()
    {
        assertThat(IdGenerator.preferredDisplayName(List.of("Sticky", "Sticky II")),
            equalTo("Sticky II"));
        assertThat(IdGenerator.preferredDisplayName(List.of("Sticky 2", "Sticky II")),
            equalTo("Sticky 2"));
        assertThat(IdGenerator.preferredDisplayName(List.of("The Sticky 2 - GM", "Sticky")),
            equalTo("The Sticky 2"));
        assertThat(IdGenerator.preferredDisplayName(List.of()), equalTo(""));
    }

    @Test
    void seriesIdsAreClubScoped()
    {
        assertThat(IdGenerator.generateSeriesId("myc.org.au", "2026 Winter Twilight"),
            equalTo("myc.org.au/2026-winter-twilight"));
        assertThat(IdGenerator.normaliseSeriesName("Main Series 2018-19"),
            equalTo("main-series-2018-19"));
    }

    @Test
    void raceIdsAreClubAndDateScoped()
    {
        assertThat(IdGenerator.generateRaceId("myc.org.au", LocalDate.of(2026, 6, 5), 1),
            equalTo("myc.org.au-2026-06-05-0001"));
        assertThat(IdGenerator.generateRaceId("myc.org.au", LocalDate.of(2026, 6, 5), 12),
            equalTo("myc.org.au-2026-06-05-0012"));
    }

    @Test
    void slashesAreSanitisedForFilenames()
    {
        // Series IDs are the only ones carrying a slash, and they key files on disk.
        assertThat(IdGenerator.sanitizeIdForFilesystem("myc.org.au/twilight"),
            equalTo("myc.org.au--twilight"));
        assertThat(IdGenerator.sanitizeIdForFilesystem("myc.org.au"), equalTo("myc.org.au"));
        assertThat(IdGenerator.sanitizeIdForFilesystem(null), equalTo(""));
    }

    @Test
    void sanitisedSeriesIdsStayDistinct()
    {
        String a = IdGenerator.sanitizeIdForFilesystem(
            IdGenerator.generateSeriesId("myc.org.au", "Twilight"));
        String b = IdGenerator.sanitizeIdForFilesystem(
            IdGenerator.generateSeriesId("myc.org.au", "Winter Twilight"));
        assertThat(a.equals(b), is(false));
    }
}
