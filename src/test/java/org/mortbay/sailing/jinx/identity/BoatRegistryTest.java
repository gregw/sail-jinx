package org.mortbay.sailing.jinx.identity;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mortbay.sailing.jinx.model.Boat;
import org.mortbay.sailing.jinx.model.Entrant;
import org.mortbay.sailing.jinx.model.RaceEntrants;
import org.mortbay.sailing.jinx.model.RaceTimes;
import org.mortbay.sailing.jinx.model.Spinnaker;
import org.mortbay.sailing.jinx.store.JsonStore;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.aMapWithSize;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;

class BoatRegistryTest
{
    private Path root;
    private JsonStore store;
    private BoatRegistry registry;

    @BeforeEach
    void setUp(@TempDir Path tmp) throws Exception
    {
        root = tmp;
        Files.createDirectories(tmp.resolve("config"));
        store = new JsonStore(tmp);
        store.start();
        rebuild("boats: {}\ndesigns: {}\n", "ignored: []\n");
    }

    /** Rebuild the registry over new config, as a restart would. */
    private void rebuild(String aliasesYaml, String designYaml) throws IOException
    {
        Files.writeString(root.resolve("config/aliases.yaml"), aliasesYaml);
        Files.writeString(root.resolve("config/design.yaml"), designYaml);
        registry = new BoatRegistry(store,
            Aliases.load(root.resolve("config")),
            DesignCatalogue.load(root.resolve("config")));
    }

    private static BoatRegistry.RawBoat raw(String sail, String name, String design)
    {
        return new BoatRegistry.RawBoat(sail, name, design, null, false);
    }

    private BoatRegistry.Resolution add(String sail, String name, String design) throws IOException
    {
        return registry.findOrCreate(raw(sail, name, design), null);
    }

    // --- creation ------------------------------------------------------------

    @Test
    void aNewBoatIsCreatedWithAReadableId() throws Exception
    {
        BoatRegistry.Resolution r = add("AUS1234", "Raging Bull", "J/24");

        assertThat(r.outcome(), equalTo(BoatRegistry.Outcome.CREATED));
        // AUS1234 canonicalises to its bare form, so AUS1234 and 1234 cannot become
        // two boats. Same rule as sailing-pf.
        assertThat(r.boat().id(), equalTo("1234-ragingbull-j24"));
        assertThat(r.boat().designId(), equalTo("j24"));
        assertThat(store.boats(), hasKey("1234-ragingbull-j24"));
    }

    @Test
    void aBoatWithNoDesignGetsADesignlessId() throws Exception
    {
        BoatRegistry.Resolution r = add("AUS1234", "Raging Bull", null);
        assertThat(r.boat().id(), equalTo("1234-ragingbull"));
        assertThat(r.boat().designId(), nullValue());
    }

    @Test
    void theSameBoatTypedTwiceIsOneRecord() throws Exception
    {
        add("AUS1234", "Raging Bull", "J/24");
        BoatRegistry.Resolution again = add("aus 1234", "raging bull", "j24");

        assertThat(again.outcome(), equalTo(BoatRegistry.Outcome.MATCHED));
        assertThat(store.boats(), aMapWithSize(1));
    }

    @Test
    void aDivisionSuffixDoesNotCreateASecondBoat() throws Exception
    {
        add("AUS1234", "Raging Bull", null);
        BoatRegistry.Resolution r = add("AUS1234", "Raging Bull - GM", null);

        assertThat(r.outcome(), equalTo(BoatRegistry.Outcome.MATCHED));
        assertThat(store.boats(), aMapWithSize(1));
        // The stored name never carries the marker.
        assertThat(r.boat().name(), equalTo("Raging Bull"));
    }

    @Test
    void aPrefixedSailNumberFindsTheBareOne() throws Exception
    {
        add("1234", "Raging Bull", null);
        BoatRegistry.Resolution r = add("AUS1234", "Raging Bull", null);

        assertThat(r.boat().id(), equalTo("1234-ragingbull"));
        assertThat(store.boats(), aMapWithSize(1));
    }

    @Test
    void aBoatNeedsSomethingToIdentifyIt() throws Exception
    {
        BoatRegistry.Resolution r = add("  ", "  ", null);
        assertThat(r.outcome(), equalTo(BoatRegistry.Outcome.CONFLICT));
        assertThat(r.resolved(), is(false));
    }

    // --- the design upgrade --------------------------------------------------

    @Test
    void learningTheDesignUpgradesTheExistingRecord() throws Exception
    {
        // The CSV had no design column the first time and did the second.
        BoatRegistry.Resolution first = add("AUS1234", "Raging Bull", null);
        assertThat(first.boat().id(), equalTo("1234-ragingbull"));

        BoatRegistry.Resolution second = add("AUS1234", "Raging Bull", "J/24");

        assertThat(second.outcome(), equalTo(BoatRegistry.Outcome.UPGRADED));
        assertThat(second.boat().id(), equalTo("1234-ragingbull-j24"));
        // Upgraded, not duplicated.
        assertThat(store.boats(), aMapWithSize(1));
        assertThat(store.boats(), not(hasKey("1234-ragingbull")));
    }

    @Test
    void anUpgradeCarriesTheBoatsHistoryWithIt() throws Exception
    {
        // The load-bearing part: the id is in entrant lists, captured times, start
        // sheets and adjustments. Miss one and the boat's races are orphaned.
        Boat boat = add("AUS1234", "Raging Bull", null).boat();
        String oldId = boat.id();

        store.putEntrants(new RaceEntrants("r-1", Instant.now(),
            RaceEntrants.TcfSource.MANUAL_EDIT, null, null,
            List.of(Entrant.fromBoat(boat, 1.0450, null, null, Entrant.EntryType.ROSTER))));
        store.putRaceTimes("r-1", new RaceTimes("r-1", List.of(oldId), oldId,
            Map.of(oldId, new RaceTimes.BoatTimes(true, "18:00:00", "19:30:00"))));

        String newId = add("AUS1234", "Raging Bull", "J/24").boat().id();
        assertThat(newId, not(equalTo(oldId)));

        assertThat(store.entrants("r-1").entrants().getFirst().boatId(), equalTo(newId));
        assertThat(store.raceTimes("r-1").times(), hasKey(newId));
        assertThat(store.raceTimes("r-1").boatOrder(), contains(newId));
        assertThat(store.raceTimes("r-1").dutyBoatId(), equalTo(newId));
        // And the finish time travelled with it, not just the key.
        assertThat(store.raceTimes("r-1").times().get(newId).finish(), equalTo("19:30:00"));
    }

    @Test
    void aKnownDesignIsNotLostWhenALaterEntryOmitsIt() throws Exception
    {
        add("AUS1234", "Raging Bull", "J/24");
        BoatRegistry.Resolution r = add("AUS1234", "Raging Bull", null);

        assertThat(r.outcome(), equalTo(BoatRegistry.Outcome.MATCHED));
        assertThat(r.boat().designId(), equalTo("j24"));
        assertThat(store.boats(), aMapWithSize(1));
    }

    @Test
    void twoDifferentDesignsAreAConflictRatherThanAGuess() throws Exception
    {
        add("AUS1234", "Raging Bull", "J/24");
        BoatRegistry.Resolution r = add("AUS1234", "Raging Bull", "Farr 40");

        // Merging would fuse two hulls; creating would split one. Neither is safe to
        // decide automatically, so a person adds an override.
        assertThat(r.outcome(), equalTo(BoatRegistry.Outcome.CONFLICT));
        assertThat(r.resolved(), is(false));
        assertThat(store.boats(), aMapWithSize(1));
    }

    // --- designs are learned, never entered ----------------------------------

    @Test
    void adesignIsLearnedTheFirstTimeItIsSeen() throws Exception
    {
        add("AUS1234", "Raging Bull", "J/24");

        assertThat(store.designs(), hasKey("j24"));
        assertThat(store.designs().get("j24").canonicalName(), equalTo("J/24"));
    }

    @Test
    void aLearnedDesignTakesItsNameFromTheAliasSeed() throws Exception
    {
        rebuild("""
            designs:
              beneteaufirst407:
                canonicalName: "Beneteau First 40.7"
                aliases:
                - "FIRST 40.7"
            boats: {}
            """, "ignored: []\n");

        BoatRegistry.Resolution r = add("AUS1", "Flashpoint", "FIRST 40.7");

        assertThat(r.boat().designId(), equalTo("beneteaufirst407"));
        assertThat(store.designs().get("beneteaufirst407").canonicalName(),
            equalTo("Beneteau First 40.7"));
    }

    @Test
    void genericLabelsAreNotTreatedAsDesigns() throws Exception
    {
        // "yacht" as a design would split this boat's history against a meaningless id.
        rebuild("boats: {}\ndesigns: {}\n", "ignored:\n- \"yacht\"\n- \"sloop\"\n");

        BoatRegistry.Resolution r = add("AUS1234", "Raging Bull", "Yacht");

        assertThat(r.boat().designId(), nullValue());
        assertThat(r.boat().id(), equalTo("1234-ragingbull"));
        assertThat(store.designs(), aMapWithSize(0));
    }

    @Test
    void aDesignThatCannotFlyASpinnakerIsFlaggedAndDefaultsEntriesToNS() throws Exception
    {
        rebuild("boats: {}\ndesigns: {}\n", "noSpinnaker:\n- \"radford12catrig\"\n");

        BoatRegistry.Resolution r = add("MYC12", "San Toy", "Radford 12 Cat Rig");

        // Not being able to fly a kite is a property of the hull, so it lives on the
        // design. What a boat actually enters on is a property of the entry, so the
        // design only supplies the default.
        assertThat(store.designs().get("radford12catrig").noSpinnaker(), is(true));
        assertThat(registry.defaultSpinnaker(r.boat().designId()), equalTo(Spinnaker.NS));

        // And a design that says nothing yields nothing. "Not recorded as unable to fly
        // one" is not evidence that a boat flies one, and the old default said S for
        // every hull in the register — so a fleet nobody had ever been asked about read
        // as if the whole lot carried kites. Unknown stays unknown; the entry says.
        assertThat(registry.defaultSpinnaker("j24"), is(nullValue()));
        assertThat(registry.defaultSpinnaker(null), is(nullValue()));
    }

    @Test
    void aPerBoatOverrideBeatsWhatWasTyped() throws Exception
    {
        rebuild("boats: {}\ndesigns: {}\n", """
            boatDesignOverrides:
            - designId: sydney36mkii
              canonicalName: "Sydney 36 MkII"
              boats:
              - sailNumber: "5915"
                name: "Stormaway"
            """);

        BoatRegistry.Resolution r = add("5915", "Stormaway", "Sydney 36");

        assertThat(r.boat().designId(), equalTo("sydney36mkii"));
        assertThat(store.designs().get("sydney36mkii").canonicalName(), equalTo("Sydney 36 MkII"));
    }

    @Test
    void aDatedOverrideOnlyAppliesToRacesInItsWindow() throws Exception
    {
        rebuild("boats: {}\ndesigns: {}\n", """
            boatDesignOverrides:
            - designId: farr40
              boats:
              - sailNumber: "1234"
                name: "Refit"
                from: 2026-01-01
            """);

        BoatRegistry.Resolution after = registry.findOrCreate(
            raw("1234", "Refit", "Sydney 38"), LocalDate.of(2026, 6, 5));
        assertThat(after.boat().designId(), equalTo("farr40"));
    }

    // --- aliases -------------------------------------------------------------

    @Test
    void aSponsorNameResolvesToTheCanonicalBoat() throws Exception
    {
        rebuild("""
            designs: {}
            boats:
              "1014-wildthing":
                canonicalName: "Wild Thing"
                aliases:
                - sailNumber: null
                  name: "UBS Wild Thing"
            """, "ignored: []\n");

        Boat original = add("1014", "Wild Thing", null).boat();
        BoatRegistry.Resolution r = add("1014", "UBS Wild Thing", null);

        assertThat(r.outcome(), equalTo(BoatRegistry.Outcome.ALIASED));
        assertThat(r.boat().id(), equalTo(original.id()));
        assertThat(store.boats(), aMapWithSize(1));
    }

    @Test
    void aNameVariantIsMatchedAndThenRememberedAsAnAlias() throws Exception
    {
        // "Sticky" and "Sticky II" are the same boat under the same sail number, and
        // nobody should have to write that down by hand.
        add("MYC7", "Sticky", null);
        BoatRegistry.Resolution r = add("MYC7", "Sticky II", null);

        assertThat(r.outcome(), equalTo(BoatRegistry.Outcome.ALIASED));
        assertThat(store.boats(), aMapWithSize(1));
        // The register settles on the fuller name, and the id moves with it.
        assertThat(r.boat().name(), equalTo("Sticky II"));
        assertThat(r.boat().id(), equalTo("MYC7-stickyii"));

        // Persisted: a fresh start resolves the supplanted spelling without rediscovering it.
        Aliases reloaded = Aliases.load(root.resolve("config"));
        assertThat(reloaded.lookupBoat("MYC7", "sticky").isPresent(), is(true));
        assertThat(reloaded.lookupBoat("MYC7", "sticky").get().normName(), equalTo("stickyii"));
    }

    @Test
    void aRecordedAliasSurvivesARestart() throws Exception
    {
        Boat boat = add("1014", "Wild Thing", null).boat();
        registry.recordAlias(boat, "AUS99", "Tribeca Wild Thing");

        rebuild(Files.readString(root.resolve("config/aliases.yaml")), "ignored: []\n");
        BoatRegistry.Resolution r = add("AUS99", "Tribeca Wild Thing", null);

        assertThat(r.boat().id(), equalTo(boat.id()));
        assertThat(store.boats(), aMapWithSize(1));
    }
}
