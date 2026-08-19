package org.mortbay.sailing.jinx.identity;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

/**
 * The assertions from sailing-pf's own {@code IdGeneratorTest}, run against this port.
 *
 * <p>The two systems hold the same fleet: sail-jinx runs the pursuit series, sailing-pf
 * analyses performance across clubs, and a boat entered in one is the same physical boat
 * as in the other. If these normalisation rules drift apart, the same hull becomes two
 * records with two histories and nobody notices until a handicap looks wrong.
 *
 * <p>So this file is a contract, not a convenience. When it fails, the question is not
 * "what should sail-jinx do?" but "which of the two projects changed, and do both agree
 * to the change?". Copied deliberately rather than shared as a library — the projects are
 * independent, and a shared jar would couple their release cycles for ~200 lines of pure
 * string handling.
 *
 * <p>Source: {@code sailing-pf/src/test/java/org/mortbay/sailing/pf/importer/IdGeneratorTest.java}
 */
class SailingPfCompatibilityTest
{
    @Test
    void stripStandardSuffixes()
    {
        assertThat(IdGenerator.stripStandardSuffixes("Foobar - GM"), equalTo("Foobar"));
        assertThat(IdGenerator.stripStandardSuffixes("Foobar-gm"), equalTo("Foobar"));
        assertThat(IdGenerator.stripStandardSuffixes("Pompus - GGM"), equalTo("Pompus"));
        assertThat(IdGenerator.stripStandardSuffixes("Pompus-GGGM"), equalTo("Pompus"));
        assertThat(IdGenerator.stripStandardSuffixes("Foobar -M"), equalTo("Foobar"));
        assertThat(IdGenerator.stripStandardSuffixes("Foobar - L"), equalTo("Foobar"));
        assertThat(IdGenerator.stripStandardSuffixes("Boat -U16"), equalTo("Boat"));
        assertThat(IdGenerator.stripStandardSuffixes("Boat - U17"), equalTo("Boat"));
        assertThat(IdGenerator.stripStandardSuffixes("Boat - U18"), equalTo("Boat"));
        assertThat(IdGenerator.stripStandardSuffixes("Boat - Under16"), equalTo("Boat"));
        assertThat(IdGenerator.stripStandardSuffixes("Boat - under18"), equalTo("Boat"));
        assertThat(IdGenerator.stripStandardSuffixes("Kilifi - Under 17"), equalTo("Kilifi"));
        assertThat(IdGenerator.stripStandardSuffixes("Boat - U 17"), equalTo("Boat"));
        assertThat(IdGenerator.stripStandardSuffixes("Boat - UNDER  18"), equalTo("Boat"));
        assertThat(IdGenerator.stripStandardSuffixes("Boat-Under 16"), equalTo("Boat"));
        assertThat(IdGenerator.stripStandardSuffixes("Boat - GM - U18"), equalTo("Boat"));
    }

    @Test
    void stripStandardSuffixesLeavesEverythingElseAlone()
    {
        assertThat(IdGenerator.stripStandardSuffixes("Boat - X"), equalTo("Boat - X"));
        assertThat(IdGenerator.stripStandardSuffixes("Boat - 2014"), equalTo("Boat - 2014"));
        assertThat(IdGenerator.stripStandardSuffixes("Boat -LL"), equalTo("Boat -LL"));
        assertThat(IdGenerator.stripStandardSuffixes("Wing-It"), equalTo("Wing-It"));
        assertThat(IdGenerator.stripStandardSuffixes("Wing-It - GM"), equalTo("Wing-It"));
        assertThat(IdGenerator.stripStandardSuffixes(null), equalTo(null));
        assertThat(IdGenerator.stripStandardSuffixes(""), equalTo(""));
        assertThat(IdGenerator.stripStandardSuffixes("   "), equalTo("   "));
    }

    @Test
    void normaliseName()
    {
        assertThat(IdGenerator.normaliseName("Foobar - GM"), equalTo("foobar"));
        assertThat(IdGenerator.normaliseName("Pompus - GGM"), equalTo("pompus"));
        assertThat(IdGenerator.normaliseName("Boat - U17"), equalTo("boat"));
        assertThat(IdGenerator.normaliseName("Foobar - L"), equalTo("foobar"));
        assertThat(IdGenerator.normaliseName("Raging Bull"), equalTo("ragingbull"));
        // normaliseName keeps the article and the numeral — only nameMatchKey drops them.
        assertThat(IdGenerator.normaliseName("The Goat"), equalTo("thegoat"));
        assertThat(IdGenerator.normaliseName("Sticky II"), equalTo("stickyii"));
        assertThat(IdGenerator.normaliseName("Sticky 2"), equalTo("sticky2"));
        assertThat(IdGenerator.normaliseName("The Sticky 2 - GM"), equalTo("thesticky2"));
    }

    @Test
    void nameMatchKey()
    {
        assertThat(IdGenerator.nameMatchKey("Goat"), equalTo("goat"));
        assertThat(IdGenerator.nameMatchKey("The Goat"), equalTo("goat"));
        assertThat(IdGenerator.nameMatchKey("THE GOAT"), equalTo("goat"));
        assertThat(IdGenerator.nameMatchKey("Sticky"), equalTo("sticky"));
        assertThat(IdGenerator.nameMatchKey("Sticky 2"), equalTo("sticky"));
        assertThat(IdGenerator.nameMatchKey("Sticky II"), equalTo("sticky"));
        assertThat(IdGenerator.nameMatchKey("Sticky ii"), equalTo("sticky"));
        assertThat(IdGenerator.nameMatchKey("Sticky II 2"), equalTo("sticky"));
        assertThat(IdGenerator.nameMatchKey("Anna 11"), equalTo("anna"));
        assertThat(IdGenerator.nameMatchKey("Anna II"), equalTo("anna"));
        assertThat(IdGenerator.nameMatchKey("The Sticky 2 - GM"), equalTo("sticky"));
        assertThat(IdGenerator.nameMatchKey("The Goat - L"), equalTo("goat"));
    }

    @Test
    void nameMatchKeyProtectsEmbeddedLetterRuns()
    {
        assertThat(IdGenerator.nameMatchKey("Thelma"), equalTo("thelma"));
        assertThat(IdGenerator.nameMatchKey("Thereby"), equalTo("thereby"));
        assertThat(IdGenerator.nameMatchKey("Tivoli"), equalTo("tivoli"));
        assertThat(IdGenerator.nameMatchKey("Pinta"), equalTo("pinta"));
        // No whitespace before the digits, so they are part of the name.
        assertThat(IdGenerator.nameMatchKey("Anna11"), equalTo("anna11"));
    }

    @Test
    void nameMatchKeyOnDegenerateInput()
    {
        assertThat(IdGenerator.nameMatchKey(null), equalTo(""));
        assertThat(IdGenerator.nameMatchKey(""), equalTo(""));
        assertThat(IdGenerator.nameMatchKey("   "), equalTo(""));
        assertThat(IdGenerator.nameMatchKey("The "), equalTo(""));
    }

    @Test
    void preferredDisplayName()
    {
        assertThat(IdGenerator.preferredDisplayName(List.of("Goat", "The Goat")), equalTo("The Goat"));
        assertThat(IdGenerator.preferredDisplayName(List.of("The Goat", "Goat")), equalTo("The Goat"));
        assertThat(IdGenerator.preferredDisplayName(List.of("Sticky", "Sticky II")), equalTo("Sticky II"));
        assertThat(IdGenerator.preferredDisplayName(List.of("Sticky 2", "Sticky II")), equalTo("Sticky 2"));
        assertThat(IdGenerator.preferredDisplayName(List.of("Sticky II", "Sticky 2")), equalTo("Sticky 2"));
        assertThat(IdGenerator.preferredDisplayName(List.of("Foobar - GM", "Foobar")), equalTo("Foobar"));
        assertThat(IdGenerator.preferredDisplayName(List.of("Foobar - GM")), equalTo("Foobar"));
        assertThat(IdGenerator.preferredDisplayName(List.of("Pompus - GGM")), equalTo("Pompus"));
        assertThat(IdGenerator.preferredDisplayName(List.of("The Sticky 2 - GM", "Sticky")),
            equalTo("The Sticky 2"));
    }

    @Test
    void preferredDisplayNameBreaksTiesInInputOrder()
    {
        // Callers pass the incoming name first, so fresh data wins an equal-length tie.
        assertThat(IdGenerator.preferredDisplayName(List.of("Goat A", "Goat B")), equalTo("Goat A"));
        assertThat(IdGenerator.preferredDisplayName(List.of("Goat B", "Goat A")), equalTo("Goat B"));
        assertThat(IdGenerator.preferredDisplayName(Arrays.asList(null, "Foobar", "")), equalTo("Foobar"));
    }
}
