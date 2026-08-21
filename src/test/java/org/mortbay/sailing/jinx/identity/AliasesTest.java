package org.mortbay.sailing.jinx.identity;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import org.eclipse.jetty.logging.StacklessLogging;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

class AliasesTest
{
    private static Aliases withYaml(Path dir, String yaml) throws Exception
    {
        Files.writeString(dir.resolve("aliases.yaml"), yaml);
        return Aliases.load(dir);
    }

    // --- implicit prefix equivalence (no YAML needed) ------------------------

    @Test
    void australianPrefixesAndLeadingZerosCollapse()
    {
        assertThat(Aliases.stripPrefix("AUS5656"), equalTo("5656"));
        assertThat(Aliases.stripPrefix("JAUS103"), equalTo("103"));
        assertThat(Aliases.stripPrefix("VAUS7"), equalTo("7"));
        assertThat(Aliases.stripPrefix("0103"), equalTo("103"));
        assertThat(Aliases.stripPrefix("AUS00103"), equalTo("103"));
        assertThat(Aliases.stripPrefix("AUS01234"), equalTo("1234"));
    }

    @Test
    void aPrefixIsOnlyStrippedWhenADigitFollows()
    {
        // "AUSTRALIA II" must not become "TRALIAII".
        assertThat(Aliases.stripPrefix("AUSTRALIA"), equalTo("AUSTRALIA"));
        // A club prefix that is not a country code is left alone.
        assertThat(Aliases.stripPrefix("MYC7"), equalTo("MYC7"));
        // A sail number that is all zeros keeps its last digit.
        assertThat(Aliases.stripPrefix("000"), equalTo("0"));
        assertThat(Aliases.stripPrefix(""), equalTo(""));
        assertThat(Aliases.stripPrefix(null), equalTo(null));
    }

    @Test
    void aPrefixedSailResolvesToItsBareFormWithNoYamlEntry(@TempDir Path dir) throws Exception
    {
        Aliases aliases = withYaml(dir, "designs: {}\nboats: {}\n");
        Optional<Aliases.BoatMatch> m = aliases.lookupBoat("AUS1234", "ragingbull");
        assertThat(m.isPresent(), is(true));
        assertThat(m.get().normSailNumber(), equalTo("1234"));
        assertThat(m.get().normName(), equalTo("ragingbull"));
    }

    @Test
    void anUnknownBoatIsNotAnAliasOfAnything(@TempDir Path dir) throws Exception
    {
        Aliases aliases = withYaml(dir, "designs: {}\nboats: {}\n");
        assertThat(aliases.lookupBoat("MYC7", "daydreaming").isPresent(), is(false));
    }

    // --- boat aliases --------------------------------------------------------

    @Test
    void aNameOnlyAliasResolvesUnderTheCanonicalSail(@TempDir Path dir) throws Exception
    {
        // The real shape of a sponsor change: same hull, same sail, new name each season.
        Aliases aliases = withYaml(dir, """
            boats:
              "1014-wildthing":
                canonicalName: "Wild Thing"
                aliases:
                - sailNumber: null
                  name: "UBS Wild Thing"
                - sailNumber: null
                  name: "Tribeca Wild Thing"
            """);

        Optional<Aliases.BoatMatch> m = aliases.lookupBoat("1014", "ubswildthing");
        assertThat(m.isPresent(), is(true));
        assertThat(m.get().normSailNumber(), equalTo("1014"));
        assertThat(m.get().normName(), equalTo("wildthing"));
        assertThat(m.get().canonicalDisplayName(), equalTo("Wild Thing"));
    }

    @Test
    void aSailOnlyAliasResolvesUnderTheCanonicalName(@TempDir Path dir) throws Exception
    {
        Aliases aliases = withYaml(dir, """
            boats:
              MYC10-joss:
                canonicalName: "Joss"
                aliases:
                - sailNumber: "RF177"
                  name: null
            """);

        Optional<Aliases.BoatMatch> m = aliases.lookupBoat("RF177", "joss");
        assertThat(m.isPresent(), is(true));
        assertThat(m.get().normSailNumber(), equalTo("MYC10"));
        assertThat(m.get().normName(), equalTo("joss"));
    }

    @Test
    void sailOnlyAndNameOnlyAliasesCrossCombine(@TempDir Path dir) throws Exception
    {
        // Neither pair is written down, but both must resolve: a boat that changed sail
        // number AND carried a sponsor name should not become a third record.
        Aliases aliases = withYaml(dir, """
            boats:
              "1014-wildthing":
                canonicalName: "Wild Thing"
                aliases:
                - sailNumber: "AUS99"
                  name: null
                - sailNumber: null
                  name: "UBS Wild Thing"
            """);

        assertThat(aliases.lookupBoat("AUS99", "ubswildthing").isPresent(), is(true));
        assertThat(aliases.lookupBoat("AUS99", "ubswildthing").get().normName(), equalTo("wildthing"));
    }

    @Test
    void anAliasKeyedUnderItsOwnCanonicalSailStillResolves(@TempDir Path dir) throws Exception
    {
        // This alias never reaches the sail index (its sail equals the canonical one), so
        // only the name branch can find it — with prefix-stripping on both sides.
        Aliases aliases = withYaml(dir, """
            boats:
              "10001-wildoats":
                canonicalName: "Wild Oats"
                aliases:
                - sailNumber: "10001"
                  name: "Hamilton Island Wild Oats"
            """);

        Optional<Aliases.BoatMatch> m = aliases.lookupBoat("AUS10001", "hamiltonislandwildoats");
        assertThat(m.isPresent(), is(true));
        assertThat(m.get().normName(), equalTo("wildoats"));
    }

    @Test
    void anEntryWhoseCanonicalNameContradictsItsKeyIsSkipped(@TempDir Path dir) throws Exception
    {
        // A hand-edit hazard: the key says one boat, canonicalName says another. Trusting
        // either would silently rewrite identities, so the entry is dropped and logged.
        Aliases aliases = withYaml(dir, """
            boats:
              "1014-wildthing":
                canonicalName: "Something Else"
                aliases:
                - sailNumber: "MYC99"
                  name: null
            """);
        // MYC99 carries no country prefix, so the implicit-equivalence rule cannot fire
        // and a present result could only have come from the (rejected) alias entry.
        assertThat(aliases.lookupBoat("MYC99", "wildthing").isPresent(), is(false));
    }

    // --- design aliases ------------------------------------------------------

    @Test
    void designAliasesResolveToTheCanonicalId(@TempDir Path dir) throws Exception
    {
        Aliases aliases = withYaml(dir, """
            designs:
              sydney36cr:
                canonicalName: "Sydney 36 CR"
                aliases:
                - "Sydney 36 OD"
            """);

        assertThat(aliases.resolveDesignAlias("sydney36od"), equalTo("sydney36cr"));
        assertThat(aliases.resolveDesignAlias("sydney36cr"), equalTo("sydney36cr"));
        assertThat(aliases.designCanonicalName("sydney36cr"), equalTo("Sydney 36 CR"));
        // Unknown designs pass through — they are new, not wrong.
        assertThat(aliases.resolveDesignAlias("farr40"), equalTo("farr40"));
    }

    // --- write-back ----------------------------------------------------------

    @Test
    void learnedAliasesArePersistedImmediately(@TempDir Path dir) throws Exception
    {
        Aliases aliases = withYaml(dir, "boats: {}\n");
        aliases.addBoatAliases("1014", "Wild Thing",
            List.of(new Aliases.SailNumberName("AUS99", "wildthing")));

        // Visible to a completely fresh load — nothing is held only in memory, because
        // the failure being defended against is the process dying without a clean stop.
        Aliases reloaded = Aliases.load(dir);
        Optional<Aliases.BoatMatch> m = reloaded.lookupBoat("AUS99", "wildthing");
        assertThat(m.isPresent(), is(true));
        assertThat(m.get().normSailNumber(), equalTo("1014"));
    }

    @Test
    void aLearnedAliasIsUsableWithoutReloading(@TempDir Path dir) throws Exception
    {
        Aliases aliases = withYaml(dir, "boats: {}\n");
        aliases.addBoatAliases("1014", "Wild Thing",
            List.of(new Aliases.SailNumberName("AUS99", "wildthing")));
        assertThat(aliases.lookupBoat("AUS99", "wildthing").isPresent(), is(true));
    }

    @Test
    void addingAnAliasTwiceDoesNotDuplicateIt(@TempDir Path dir) throws Exception
    {
        Aliases aliases = withYaml(dir, "boats: {}\n");
        Aliases.SailNumberName alias = new Aliases.SailNumberName("AUS99", "wildthing");
        aliases.addBoatAliases("1014", "Wild Thing", List.of(alias));
        aliases.addBoatAliases("1014", "Wild Thing", List.of(alias));
        assertThat(aliases.boatAliases("1014", "wildthing"), hasSize(1));
    }

    @Test
    void anOrphanEntryIsAbsorbedWhenItsIdentityIsMergedAway(@TempDir Path dir) throws Exception
    {
        // "AUS99-oldname" was a boat in its own right and had its own aliases. Merging it
        // into 1014-wildthing must carry those aliases across and delete the old key —
        // otherwise the next import of "veryoldname" resolves back to the dead identity
        // and re-creates the boat.
        Aliases aliases = withYaml(dir, """
            boats:
              "AUS99-oldname":
                canonicalName: "Oldname"
                aliases:
                - sailNumber: "AUS99"
                  name: "veryoldname"
            """);

        aliases.addBoatAliases("1014", "Wild Thing",
            List.of(new Aliases.SailNumberName("AUS99", "oldname")));

        Aliases reloaded = Aliases.load(dir);
        assertThat(reloaded.lookupBoat("AUS99", "oldname").get().normName(), equalTo("wildthing"));
        // The absorbed alias now points at the surviving identity too.
        assertThat(reloaded.lookupBoat("AUS99", "veryoldname").get().normName(), equalTo("wildthing"));
        // And the orphan key is gone.
        assertThat(reloaded.boatAliases("AUS99", "oldname"), hasSize(0));
    }

    @Test
    void anUnreadableFileIsNeverOverwritten(@TempDir Path dir) throws Exception
    {
        // Half-finished hand edit. Rewriting it would destroy every alias in it, so the
        // write is refused loudly instead.
        Files.writeString(dir.resolve("aliases.yaml"), "boats: {}\n");
        Aliases aliases = Aliases.load(dir);
        String broken = "boats:\n  \"1014-wildthing\":\n    canonicalName: \"Wild\n";
        Files.writeString(dir.resolve("aliases.yaml"), broken);

        // Refusing loudly is the behaviour under test, so its stack trace is expected
        // output rather than a problem. Hidden for this block only.
        try (StacklessLogging ignored = new StacklessLogging(Aliases.class))
        {
            aliases.addBoatAliases("1014", "Wild Thing",
                List.of(new Aliases.SailNumberName("AUS99", "wildthing")));
            throw new AssertionError("expected the write to be refused");
        }
        catch (IllegalStateException expected)
        {
            assertThat(Files.readString(dir.resolve("aliases.yaml")), equalTo(broken));
        }
    }

    @Test
    void aMissingFileIsNotAnError(@TempDir Path dir)
    {
        Aliases aliases = Aliases.load(dir);
        assertThat(aliases.lookupBoat("MYC7", "daydreaming").isPresent(), is(false));
        assertThat(aliases.resolveDesignAlias("j24"), equalTo("j24"));
    }

    // --- the real seed -------------------------------------------------------

    @Test
    void theShippedSeedLoadsAndResolves()
    {
        // Guards the seed itself: 173 designs and 434 boats hand-maintained in sailing-pf,
        // copied here. A malformed entry should surface as a test failure, not as a
        // mystery duplicate boat months later.
        Path config = Path.of("data/config");
        Aliases aliases = Aliases.load(config);

        assertThat(aliases.resolveDesignAlias("sydney36od"), equalTo("sydney36cr"));
        assertThat(aliases.designCanonicalName("beneteaufirst407"),
            equalTo("Beneteau First 40.7"));

        Optional<Aliases.BoatMatch> comanche = aliases.lookupBoat("CAY007", "andoocomanche");
        assertThat(comanche.isPresent(), is(true));
        assertThat(comanche.get().normName(), equalTo("comanche"));

        Optional<Aliases.BoatMatch> joss = aliases.lookupBoat("RF177", "joss");
        assertThat(joss.isPresent(), is(true));
        assertThat(joss.get().normSailNumber(), equalTo("MYC10"));
        assertThat(joss.get().canonicalDisplayName(), notNullValue());
    }
}
