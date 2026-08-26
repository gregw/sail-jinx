package org.mortbay.sailing.jinx.store;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

import org.eclipse.jetty.logging.StacklessLogging;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mortbay.sailing.jinx.config.JinxConfig;
import org.mortbay.sailing.jinx.model.Adjustment;
import org.mortbay.sailing.jinx.model.AuditEntry;
import org.mortbay.sailing.jinx.model.Boat;
import org.mortbay.sailing.jinx.model.Entrant;
import org.mortbay.sailing.jinx.model.Race;
import org.mortbay.sailing.jinx.model.RaceEntrants;
import org.mortbay.sailing.jinx.model.RaceTimes;
import org.mortbay.sailing.jinx.model.Series;
import org.mortbay.sailing.jinx.model.Spinnaker;
import org.mortbay.sailing.jinx.model.StartSheet;
import org.mortbay.sailing.jinx.model.StartTime;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.aMapWithSize;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;

class JsonStoreTest
{
    private static Boat boat(String id, String sail, String name)
    {
        return new Boat(id, sail, name, null, false, true, null);
    }

    private static Entrant entrant(Boat b, double tcf)
    {
        return Entrant.fromBoat(b, tcf, null, null, Entrant.EntryType.ROSTER);
    }

    @Test
    void emptyStoreStartsClean(@TempDir Path tmp) throws IOException
    {
        JsonStore store = new JsonStore(tmp);
        store.start();
        assertThat(store.boats().entrySet(), hasSize(0));
        assertThat(store.races().entrySet(), hasSize(0));
        assertThat(store.series().entrySet(), hasSize(0));
        assertThat(store.audit(), hasSize(0));
    }

    // --- Fleet register ------------------------------------------------------

    @Test
    void putBoatRoundTripsAcrossRestart(@TempDir Path tmp) throws IOException
    {
        JsonStore first = new JsonStore(tmp);
        first.start();
        first.putBoat(new Boat("b-1", "AUS5678", "Flashpoint", "j24", false, true, "spare main"));

        JsonStore reopened = new JsonStore(tmp);
        reopened.start();
        assertThat(reopened.boats(), aMapWithSize(1));
        Boat read = reopened.boats().get("b-1");
        assertThat(read.name(), equalTo("Flashpoint"));
        assertThat(read.sailNumber(), equalTo("AUS5678"));
        assertThat(read.designId(), equalTo("j24"));
        assertThat(read.active(), is(true));
        assertThat(read.notes(), equalTo("spare main"));
    }

    @Test
    void retiredBoatsStayInTheRegister(@TempDir Path tmp) throws IOException
    {
        // Retiring is a flag, never a delete: past races reference the boat and
        // must keep rendering its name and sail number.
        JsonStore store = new JsonStore(tmp);
        store.start();
        store.putBoat(boat("b-1", "AUS1", "Gone Fishing"));
        store.putBoat(new Boat("b-1", "AUS1", "Gone Fishing", null, false, false, null));

        assertThat(store.boats(), aMapWithSize(1));
        assertThat(store.boats().get("b-1").active(), is(false));
    }

    // --- Series --------------------------------------------------------------

    @Test
    void seriesRoundTripsAcrossRestart(@TempDir Path tmp) throws IOException
    {
        JsonStore first = new JsonStore(tmp);
        first.start();
        first.putSeries(new Series("s-1", "2026 Winter Twilight", false));

        JsonStore reopened = new JsonStore(tmp);
        reopened.start();
        Series read = reopened.series().get("s-1");
        assertThat(read.name(), equalTo("2026 Winter Twilight"));
        assertThat(read.archived(), is(false));
    }

    // --- Races ---------------------------------------------------------------

    @Test
    void putRaceRoundTripsAcrossRestart(@TempDir Path tmp) throws IOException
    {
        JsonStore first = new JsonStore(tmp);
        first.start();
        first.putRace(new Race("r-1", "s-1", 6, "Twilight R06",
            LocalDate.of(2026, 6, 5), LocalTime.of(18, 0), 60, false));

        JsonStore reopened = new JsonStore(tmp);
        reopened.start();
        Race read = reopened.races().get("r-1");
        assertThat(read.seriesId(), equalTo("s-1"));
        assertThat(read.number(), equalTo(6));
        assertThat(read.date(), equalTo(LocalDate.of(2026, 6, 5)));
        assertThat(read.earliestStart(), equalTo(LocalTime.of(18, 0)));
        assertThat(read.targetElapsedMinutes(), equalTo(60));
        assertThat(read.abandoned(), is(false));
    }

    @Test
    void racesForSeriesAreFilteredAndOrderedByNumber(@TempDir Path tmp) throws IOException
    {
        JsonStore store = new JsonStore(tmp);
        store.start();
        store.putRace(new Race("r-3", "s-1", 3, "R3", LocalDate.of(2026, 6, 19),
            LocalTime.of(18, 0), 60, false));
        store.putRace(new Race("r-1", "s-1", 1, "R1", LocalDate.of(2026, 6, 5),
            LocalTime.of(18, 0), 60, false));
        store.putRace(new Race("r-2", "s-1", 2, "R2", LocalDate.of(2026, 6, 12),
            LocalTime.of(18, 0), 60, false));
        store.putRace(new Race("x-1", "s-2", 1, "Other series", LocalDate.of(2026, 6, 6),
            LocalTime.of(14, 0), 90, false));

        assertThat(store.racesInSeries("s-1").stream().map(Race::id).toList(),
            contains("r-1", "r-2", "r-3"));
        assertThat(store.racesInSeries("s-2").stream().map(Race::id).toList(),
            contains("x-1"));
        assertThat(store.racesInSeries("nope"), hasSize(0));
    }

    @Test
    void nextRaceInSeriesIsTheFollowingNumber(@TempDir Path tmp) throws IOException
    {
        // Save Handicaps on race N writes race N+1's entrant TCFs, so the store
        // has to answer "which race follows this one" without SailSys's
        // nextRaceId field.
        JsonStore store = new JsonStore(tmp);
        store.start();
        store.putRace(new Race("r-1", "s-1", 1, "R1", LocalDate.of(2026, 6, 5),
            LocalTime.of(18, 0), 60, false));
        store.putRace(new Race("r-2", "s-1", 2, "R2", LocalDate.of(2026, 6, 12),
            LocalTime.of(18, 0), 60, false));

        assertThat(store.nextRaceInSeries("r-1").map(Race::id).orElse(null), equalTo("r-2"));
        assertThat(store.nextRaceInSeries("r-2").isPresent(), is(false));
        assertThat(store.nextRaceInSeries("nope").isPresent(), is(false));
    }

    // --- Race entrants -------------------------------------------------------

    @Test
    void entrantsAreNullWhenMissing(@TempDir Path tmp) throws IOException
    {
        JsonStore store = new JsonStore(tmp);
        store.start();
        assertThat(store.entrants("r-1"), nullValue());
    }

    @Test
    void entrantsRoundTripAcrossRestart(@TempDir Path tmp) throws IOException
    {
        JsonStore first = new JsonStore(tmp);
        first.start();
        first.putEntrants(new RaceEntrants("r-2", Instant.parse("2026-06-12T07:00:00Z"),
            RaceEntrants.TcfSource.CARRIED_FORWARD, "r-1", 1,
            List.of(
                entrant(boat("b-1", "AUS1", "Flashpoint"), 1.0666),
                entrant(boat("b-2", "AUS2", "Slow Poke"), 0.9485))));

        JsonStore reopened = new JsonStore(tmp);
        reopened.start();
        RaceEntrants read = reopened.entrants("r-2");
        assertThat(read.raceId(), equalTo("r-2"));
        assertThat(read.tcfSource(), equalTo(RaceEntrants.TcfSource.CARRIED_FORWARD));
        assertThat(read.sourceRaceId(), equalTo("r-1"));
        assertThat(read.sourceRaceNumber(), equalTo(1));
        assertThat(read.entrants(), hasSize(2));
        assertThat(read.entrants().get(0).boatId(), equalTo("b-1"));
        assertThat(read.entrants().get(0).sailNumber(), equalTo("AUS1"));
    }

    @Test
    void entrantsCarryTheirOwnTcfSoPastRacesKeepTheirHistory(@TempDir Path tmp) throws IOException
    {
        // The whole point of storing entrants per race: race 1's TCFs must
        // survive race 2 being processed. SailSys only ever kept the latest.
        JsonStore store = new JsonStore(tmp);
        store.start();
        Boat b = boat("b-1", "AUS1", "Flashpoint");
        store.putEntrants(new RaceEntrants("r-1", Instant.now(),
            RaceEntrants.TcfSource.MANUAL_EDIT, null, null,
            List.of(entrant(b, 1.0450))));
        store.putEntrants(new RaceEntrants("r-2", Instant.now(),
            RaceEntrants.TcfSource.CARRIED_FORWARD, "r-1", 1,
            List.of(entrant(b, 1.0666))));

        assertThat(store.entrants("r-1").entrants().get(0).tcf(), equalTo(1.0450));
        assertThat(store.entrants("r-2").entrants().get(0).tcf(), equalTo(1.0666));
    }

    @Test
    void tcfIsStoredAtFourDecimals(@TempDir Path tmp) throws IOException
    {
        // Four decimals is the sailing convention, and these numbers get read
        // out loud and retyped into another system by hand — see Tcf. The
        // engine's full-precision output is quantised as it is recorded, not
        // left to render differently every time it is displayed.
        JsonStore first = new JsonStore(tmp);
        first.start();
        first.putEntrants(new RaceEntrants("r-1", Instant.now(),
            RaceEntrants.TcfSource.MANUAL_EDIT, null, null,
            List.of(entrant(boat("b-1", "AUS1", "Flashpoint"), 0.9287868))));

        JsonStore reopened = new JsonStore(tmp);
        reopened.start();
        assertThat(reopened.entrants("r-1").entrants().get(0).tcf(), equalTo(0.9288));
    }

    @Test
    void aFullPrecisionTcfOnDiskIsQuantisedOnRead(@TempDir Path tmp) throws IOException
    {
        // Files written before the four-decimal rule — or edited by hand —
        // must not reintroduce long tails through the back door.
        JsonStore store = new JsonStore(tmp);
        store.start();
        Files.writeString(tmp.resolve("store/entrants/r-1.json"), """
            {"raceId":"r-1","tcfSource":"ROSTER","entrants":[
              {"boatId":"b-1","sailNumber":"AUS1","name":"Flashpoint","tcf":0.92878681}]}""",
            StandardCharsets.UTF_8);

        assertThat(store.entrants("r-1").entrants().get(0).tcf(), equalTo(0.9288));
    }

    @Test
    void anEntrantListWrittenWhenTheRosterExistedStillLoads(@TempDir Path tmp) throws IOException
    {
        // The series roster is gone and nothing writes TcfSource.ROSTER any more, but the
        // club's store is the only copy there is and its early races still say it. The
        // constant stays for exactly this: dropping it would make Jackson throw on a file
        // nobody can regenerate.
        JsonStore store = new JsonStore(tmp);
        store.start();
        Files.writeString(tmp.resolve("store/entrants/r-1.json"), """
            {"raceId":"r-1","tcfSource":"ROSTER","entrants":[
              {"boatId":"b-1","sailNumber":"AUS1","name":"Flashpoint","tcf":1.0450}]}""",
            StandardCharsets.UTF_8);

        RaceEntrants read = store.entrants("r-1");
        assertThat(read.tcfSource(), equalTo(RaceEntrants.TcfSource.ROSTER));
        assertThat(read.entrants().getFirst().boatId(), equalTo("b-1"));
    }

    @Test
    void oneOffEntrantHasNoRegisterBoat(@TempDir Path tmp) throws IOException
    {
        // Outcome 3 of the casual flow: sailed once, never to be seen again.
        // No register entry, no boatId, and excluded from handicap processing.
        JsonStore first = new JsonStore(tmp);
        first.start();
        first.putEntrants(new RaceEntrants("r-1", Instant.now(),
            RaceEntrants.TcfSource.MANUAL_EDIT, null, null,
            List.of(Entrant.oneOff("Visitor", "??? 42", 1.0))));

        JsonStore reopened = new JsonStore(tmp);
        reopened.start();
        Entrant read = reopened.entrants("r-1").entrants().get(0);
        assertThat(read.boatId(), nullValue());
        assertThat(read.name(), equalTo("Visitor"));
        assertThat(read.entryType(), equalTo(Entrant.EntryType.ONE_OFF));
        assertThat(read.scoresHandicap(), is(false));
    }

    // --- Start sheet ---------------------------------------------------------

    @Test
    void startSheetIsNullWhenMissing(@TempDir Path tmp) throws IOException
    {
        JsonStore store = new JsonStore(tmp);
        store.start();
        assertThat(store.startSheet("r-1"), nullValue());
    }

    @Test
    void startSheetRoundTripsAcrossRestart(@TempDir Path tmp) throws IOException
    {
        JsonStore first = new JsonStore(tmp);
        first.start();
        first.putStartSheet(new StartSheet("r-1", Instant.parse("2026-06-05T06:00:00Z"),
            90, LocalTime.of(18, 0), List.of(
                new StartTime("b-2", 0.9340, 101.2, LocalTime.of(18, 0)),
                new StartTime("b-1", 1.0450, 88.4, LocalTime.of(18, 13)))));

        JsonStore reopened = new JsonStore(tmp);
        reopened.start();
        StartSheet read = reopened.startSheet("r-1");
        assertThat(read.targetElapsedMinutes(), equalTo(90));
        assertThat(read.earliestStart(), equalTo(LocalTime.of(18, 0)));
        assertThat(read.starts(), hasSize(2));
        assertThat(read.starts().get(0).boatId(), equalTo("b-2"));
        assertThat(read.starts().get(1).startTime(), equalTo(LocalTime.of(18, 13)));
    }

    // --- Race times (RO-captured wall clock) ---------------------------------

    @Test
    void raceTimesAreNullWhenMissing(@TempDir Path tmp) throws IOException
    {
        JsonStore store = new JsonStore(tmp);
        store.start();
        assertThat(store.raceTimes("never-existed"), nullValue());
    }

    @Test
    void raceTimesRoundTripAcrossRestart(@TempDir Path tmp) throws IOException
    {
        JsonStore first = new JsonStore(tmp);
        first.start();
        first.putRaceTimes("r-9", new RaceTimes(
            "r-9",
            List.of("b-1", "b-2", "b-3"),
            "b-3",
            Map.of(
                "b-1", new RaceTimes.BoatTimes(true,  "18:13:02", "19:07:11"),
                "b-2", new RaceTimes.BoatTimes(true,  "18:10:00", "19:05:22"),
                "b-3", new RaceTimes.BoatTimes(false, null,       null))));

        JsonStore reopened = new JsonStore(tmp);
        reopened.start();
        RaceTimes read = reopened.raceTimes("r-9");
        assertThat(read.raceId(), equalTo("r-9"));
        assertThat(read.boatOrder(), contains("b-1", "b-2", "b-3"));
        assertThat(read.dutyBoatId(), equalTo("b-3"));
        assertThat(read.times().get("b-1").actualStart(), equalTo("18:13:02"));
        assertThat(read.times().get("b-1").finish(), equalTo("19:07:11"));
        assertThat(read.times().get("b-3").came(), is(false));
    }

    @Test
    void raceTimesDutyBoatNullablePersists(@TempDir Path tmp) throws IOException
    {
        JsonStore first = new JsonStore(tmp);
        first.start();
        first.putRaceTimes("r-10", new RaceTimes(
            "r-10", List.of("b-1"), null,
            Map.of("b-1", new RaceTimes.BoatTimes(true, "18:13:02", "19:07:11"))));

        JsonStore reopened = new JsonStore(tmp);
        reopened.start();
        assertThat(reopened.raceTimes("r-10").dutyBoatId(), nullValue());
    }

    // --- Per-series algorithm config -----------------------------------------

    @Test
    void seriesConfigIsNullWhenMissing(@TempDir Path tmp) throws IOException
    {
        JsonStore store = new JsonStore(tmp);
        store.start();
        assertThat(store.seriesConfig("s-1"), nullValue());
    }

    @Test
    void seriesConfigRoundTripsAcrossRestart(@TempDir Path tmp) throws IOException
    {
        JsonStore first = new JsonStore(tmp);
        first.start();
        first.putSeriesConfig("s-1",
            new JinxConfig.Algorithm(List.of(7.0, 5.0, 3.0, 1.0), 75, 4, "17:30",
                -34.5678, 150.4321, true,
                JinxConfig.Variant.B, null, null, null));

        JsonStore reopened = new JsonStore(tmp);
        reopened.start();
        JinxConfig.Algorithm read = reopened.seriesConfig("s-1");
        assertThat(read.penaltyList(), contains(7.0, 5.0, 3.0, 1.0));
        assertThat(read.defaultRaceDuration(), equalTo(75));
        assertThat(read.dnfAllowance(), equalTo(4));
        assertThat(read.earliestStart(), equalTo("17:30"));
        assertThat(read.limitBySunset(), is(true));
        // A per-series variant override has to survive the round trip, or a series
        // scored on B quietly reverts to the club default on restart.
        assertThat(read.penaltyScaling(), is(JinxConfig.PenaltyScaling.FIXED));
        assertThat(read.givebackGamma(), is(1.0));
    }

    // --- Adjustments and audit -----------------------------------------------

    @Test
    void savedAdjustmentsAreEmptyWhenMissing(@TempDir Path tmp) throws IOException
    {
        JsonStore store = new JsonStore(tmp);
        store.start();
        assertThat(store.adjustments("r-1"), hasSize(0));
    }

    @Test
    void savedAdjustmentsRoundTripAcrossRestart(@TempDir Path tmp) throws IOException
    {
        JsonStore first = new JsonStore(tmp);
        first.start();
        first.putAdjustments("r-7", List.of(
            new Adjustment("b-1", 1, 5.0, 1.71, 3.29, 1.0450, 1.0666),
            new Adjustment("b-2", 2, 4.0, 1.76, 2.24, 0.9340, 0.9485)));

        JsonStore reopened = new JsonStore(tmp);
        reopened.start();
        List<Adjustment> read = reopened.adjustments("r-7");
        assertThat(read, hasSize(2));
        assertThat(read.get(0).newTcf(), equalTo(1.0666));
        assertThat(read.get(1).netAdjustmentMinutes(), equalTo(2.24));
    }

    @Test
    void deletingAdjustmentsUnlocksTheRace(@TempDir Path tmp) throws IOException
    {
        // The race lifecycle is derived, not stored: a race is locked once its
        // handicaps are saved, and Unlock is exactly "drop the adjustments".
        JsonStore store = new JsonStore(tmp);
        store.start();
        store.putAdjustments("r-1", List.of(
            new Adjustment("b-1", 1, 5.0, 1.71, 3.29, 1.0450, 1.0666)));
        assertThat(store.adjustments("r-1"), hasSize(1));

        assertThat(store.deleteAdjustments("r-1"), is(true));
        assertThat(store.adjustments("r-1"), hasSize(0));
        assertThat(store.deleteAdjustments("r-1"), is(false));
    }

    @Test
    void auditAppendIsOrderedAndPersisted(@TempDir Path tmp) throws IOException
    {
        JsonStore first = new JsonStore(tmp);
        first.start();
        first.appendAudit(new AuditEntry(Instant.parse("2026-06-05T10:00:00Z"),
            "r-1", "process", 0.4, 15.0, List.of(), "first"));
        first.appendAudit(new AuditEntry(Instant.parse("2026-06-05T10:05:00Z"),
            "r-1", "save", 0.4, 15.0,
            List.of(new Adjustment("b-1", 1, 5.0, 1.68, 3.32, 1.0450, 1.0666)),
            "second"));

        JsonStore reopened = new JsonStore(tmp);
        reopened.start();
        assertThat(reopened.audit(), hasSize(2));
        assertThat(reopened.audit().stream().map(AuditEntry::notes).toList(),
            contains("first", "second"));
    }

    @Test
    void anAuditLogWrittenBeforeItRecordedTheUserStillLoads(@TempDir Path tmp)
        throws IOException
    {
        // Exactly what audit.json holds on a server installed before the field existed.
        // The club's is the only copy of that history, so it has to survive the upgrade
        // that added the column — a log that fails to load is a log that is gone.
        Files.createDirectories(tmp.resolve("store"));
        Files.writeString(tmp.resolve("store/audit.json"), """
            [{"timestamp":"2026-06-05T10:00:00Z","raceId":"r-1","action":"save-handicaps",
              "gamma":0.0,"penaltyPool":15.0,"adjustments":[],"notes":"before"}]""");

        JsonStore store = new JsonStore(tmp);
        store.start();

        assertThat(store.loadErrors(), hasSize(0));
        assertThat(store.audit(), hasSize(1));
        assertThat(store.audit().get(0).notes(), equalTo("before"));
        // Null, not "", and not a placeholder: nobody can be named for it.
        assertThat(store.audit().get(0).user(), nullValue());
    }

    // --- Durability ----------------------------------------------------------
    //
    // These matter more than they used to. With SailSys gone this store is the
    // only record of a season; there is nothing to re-fetch from.

    @Test
    void writesLeaveNoTemporaryFilesBehind(@TempDir Path tmp) throws IOException
    {
        JsonStore store = new JsonStore(tmp);
        store.start();
        store.putBoat(boat("b-1", "AUS1", "Flashpoint"));
        store.putRace(new Race("r-1", "s-1", 1, "R1", LocalDate.of(2026, 6, 5),
            LocalTime.of(18, 0), 60, false));
        store.putRaceTimes("r-1", new RaceTimes("r-1", List.of(), null, Map.of()));

        try (var walk = Files.walk(tmp))
        {
            assertThat(walk.filter(Files::isRegularFile)
                    .map(p -> p.getFileName().toString())
                    .filter(n -> n.endsWith(".tmp"))
                    .toList(),
                hasSize(0));
        }
    }

    @Test
    void aCorruptPerRaceFileDoesNotPreventStartup(@TempDir Path tmp) throws IOException
    {
        // Hand-editing the JSON on race night is a supported repair path, which
        // means a half-edited file will eventually happen. One bad file must
        // not take the whole app down with it.
        JsonStore first = new JsonStore(tmp);
        first.start();
        first.putRaceTimes("r-1", new RaceTimes("r-1", List.of("b-1"), null,
            Map.of("b-1", new RaceTimes.BoatTimes(true, "18:00:00", "19:30:00"))));
        first.putRaceTimes("r-2", new RaceTimes("r-2", List.of("b-1"), null,
            Map.of("b-1", new RaceTimes.BoatTimes(true, "18:00:00", "19:31:00"))));

        Files.writeString(tmp.resolve("store/race-times/r-1.json"), "{ not json",
            StandardCharsets.UTF_8);

        // The parse failure is the point of the test, and JsonStore logs it with the
        // stack trace a real one deserves. Hidden here so a green build stays readable —
        // scoped to this block, so an unexpected trace anywhere else still shows.
        try (StacklessLogging ignored = new StacklessLogging(JsonStore.class))
        {
            JsonStore reopened = new JsonStore(tmp);
            reopened.start();
            assertThat(reopened.raceTimes("r-1"), nullValue());
            assertThat(reopened.raceTimes("r-2").times().get("b-1").finish(),
                equalTo("19:31:00"));
            assertThat(reopened.loadErrors(), hasSize(1));
            assertThat(reopened.loadErrors().get(0), not(nullValue()));
        }
    }

    @Test
    void aCorruptTopLevelFileDoesNotPreventStartup(@TempDir Path tmp) throws IOException
    {
        JsonStore first = new JsonStore(tmp);
        first.start();
        first.putBoat(boat("b-1", "AUS1", "Flashpoint"));
        Files.writeString(tmp.resolve("store/boats.json"), "]]] nope",
            StandardCharsets.UTF_8);

        try (StacklessLogging ignored = new StacklessLogging(JsonStore.class))
        {
            JsonStore reopened = new JsonStore(tmp);
            reopened.start();
            assertThat(reopened.boats().entrySet(), hasSize(0));
            assertThat(reopened.loadErrors(), hasSize(1));
        }
    }

    @Test
    void aStoreDirectoryThatVanishesIsRecreatedOnTheNextWrite(@TempDir Path tmp) throws IOException
    {
        // A stray rm, an unmounted share, a backup script that moved instead of
        // copied. Whatever the cause, the next save should heal it rather than
        // failing until somebody restarts the server mid-race.
        JsonStore store = new JsonStore(tmp);
        store.start();
        store.putBoat(boat("b-1", "AUS1", "Flashpoint"));

        deleteRecursively(tmp.resolve("store"));

        store.putBoat(boat("b-2", "AUS2", "Slow Poke"));
        store.putRaceTimes("r-1", new RaceTimes("r-1", List.of(), null, Map.of()));

        JsonStore reopened = new JsonStore(tmp);
        reopened.start();
        // b-1 is gone with the directory — nothing can bring that back. What
        // matters is that the writes after the loss landed.
        assertThat(reopened.boats().get("b-2").name(), equalTo("Slow Poke"));
        assertThat(reopened.raceTimes("r-1").raceId(), equalTo("r-1"));
    }

    private static void deleteRecursively(Path dir) throws IOException
    {
        try (var walk = Files.walk(dir))
        {
            for (Path p : walk.sorted(java.util.Comparator.reverseOrder()).toList())
                Files.deleteIfExists(p);
        }
    }

    @Test
    void everyMutationIsJournalled(@TempDir Path tmp) throws IOException
    {
        JsonStore store = new JsonStore(tmp);
        store.start();
        store.putBoat(boat("b-1", "AUS1", "Flashpoint"));
        store.putSeries(new Series("s-1", "2026 Winter Twilight", false));
        store.putRaceTimes("r-1", new RaceTimes("r-1", List.of(), null, Map.of()));

        List<String> lines = journalLines(tmp);
        assertThat(lines, hasSize(3));
        // One self-describing JSON object per line: enough to rebuild any file
        // by replay, and readable with `tail -f` while a race is running.
        assertThat(lines.get(0), containsAll("\"entity\":\"boats\"", "\"key\":\"b-1\""));
        assertThat(lines.get(1), containsAll("\"entity\":\"series\"", "\"key\":\"s-1\""));
        assertThat(lines.get(2), containsAll("\"entity\":\"race-times\"", "\"key\":\"r-1\""));
    }

    @Test
    void journalRecordsDeletions(@TempDir Path tmp) throws IOException
    {
        JsonStore store = new JsonStore(tmp);
        store.start();
        store.putAdjustments("r-1", List.of(
            new Adjustment("b-1", 1, 5.0, 1.71, 3.29, 1.0450, 1.0666)));
        store.deleteAdjustments("r-1");

        List<String> lines = journalLines(tmp);
        assertThat(lines, hasSize(2));
        assertThat(lines.get(1), containsAll("\"entity\":\"adjustments\"", "\"deleted\":true"));
    }

    private static List<String> journalLines(Path tmp) throws IOException
    {
        Path dir = tmp.resolve("store/journal");
        try (var files = Files.list(dir))
        {
            List<Path> all = files.sorted().toList();
            assertThat(all, hasSize(1));
            return Files.readAllLines(all.get(0), StandardCharsets.UTF_8).stream()
                .filter(l -> !l.isBlank())
                .toList();
        }
    }

    private static org.hamcrest.Matcher<String> containsAll(String... needles)
    {
        return new org.hamcrest.TypeSafeMatcher<>()
        {
            @Override
            protected boolean matchesSafely(String actual)
            {
                for (String n : needles)
                {
                    if (!actual.contains(n)) return false;
                }
                return true;
            }

            @Override
            public void describeTo(org.hamcrest.Description description)
            {
                description.appendText("a string containing all of ")
                    .appendValue(List.of(needles));
            }
        };
    }
}
