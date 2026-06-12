package org.mortbay.sailing.jinx.store;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mortbay.sailing.jinx.config.JinxConfig;
import org.mortbay.sailing.jinx.model.Adjustment;
import org.mortbay.sailing.jinx.model.AuditEntry;
import org.mortbay.sailing.jinx.model.Boat;
import org.mortbay.sailing.jinx.model.Calibration;
import org.mortbay.sailing.jinx.model.FinishStatus;
import org.mortbay.sailing.jinx.model.Race;
import org.mortbay.sailing.jinx.model.RaceStatus;
import org.mortbay.sailing.jinx.model.RaceTimes;
import org.mortbay.sailing.jinx.model.Result;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.aMapWithSize;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasEntry;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

class JsonStoreTest
{
    @Test
    void emptyStoreStartsClean(@TempDir Path tmp) throws IOException
    {
        JsonStore store = new JsonStore(tmp);
        store.start();
        assertThat(store.boats().entrySet(), hasSize(0));
        assertThat(store.races().entrySet(), hasSize(0));
        assertThat(store.audit(), hasSize(0));
    }

    @Test
    void putBoatRoundTripsAcrossRestart(@TempDir Path tmp) throws IOException
    {
        JsonStore first = new JsonStore(tmp);
        first.start();
        first.putBoat(new Boat("12345", "Flashpoint", "AUS5678", "13779", "Div 1", 1.0450));

        JsonStore reopened = new JsonStore(tmp);
        reopened.start();
        assertThat(reopened.boats(), aMapWithSize(1));
        Boat boat = reopened.boats().get("12345");
        assertThat(boat.name(), equalTo("Flashpoint"));
        assertThat(boat.sailNumber(), equalTo("AUS5678"));
        assertThat(boat.divisionId(), equalTo("13779"));
        assertThat(boat.currentTcf(), equalTo(1.0450));
    }

    @Test
    void replaceBoatsOverwritesEverything(@TempDir Path tmp) throws IOException
    {
        JsonStore store = new JsonStore(tmp);
        store.start();
        store.putBoat(new Boat("1", "Old", "AUS1", "d", "Div", 1.0));
        store.replaceBoats(Map.of(
            "2", new Boat("2", "New", "AUS2", "d", "Div", 0.95)));
        assertThat(store.boats(), aMapWithSize(1));
        assertThat(store.boats(), hasEntry(is("2"), is(new Boat("2", "New", "AUS2", "d", "Div", 0.95))));
    }

    @Test
    void putRaceRoundTripsAcrossRestart(@TempDir Path tmp) throws IOException
    {
        JsonStore first = new JsonStore(tmp);
        first.start();
        first.putRace(new Race(
            "race-1", 6, "Twilight R06",
            LocalDate.of(2026, 6, 5), 60, LocalTime.of(18, 0),
            RaceStatus.SCHEDULED));

        JsonStore reopened = new JsonStore(tmp);
        reopened.start();
        Race race = reopened.races().get("race-1");
        assertThat(race.number(), equalTo(6));
        assertThat(race.date(), equalTo(LocalDate.of(2026, 6, 5)));
        assertThat(race.earliestStart(), equalTo(LocalTime.of(18, 0)));
        assertThat(race.status(), equalTo(RaceStatus.SCHEDULED));
    }

    @Test
    void resultsAreStoredPerRace(@TempDir Path tmp) throws IOException
    {
        JsonStore store = new JsonStore(tmp);
        store.start();
        Result fin = new Result("12345", FinishStatus.FIN,
            LocalTime.of(18, 13), LocalTime.of(19, 7), null);
        Result dnf = new Result("99999", FinishStatus.DNF,
            LocalTime.of(18, 0), null, null);
        store.putResults("race-1", Map.of("12345", fin, "99999", dnf));

        Map<String, Result> read = store.results("race-1");
        assertThat(read, aMapWithSize(2));
        assertThat(read.get("12345").status(), equalTo(FinishStatus.FIN));
        assertThat(read.get("99999").status(), equalTo(FinishStatus.DNF));
        assertThat(store.results("never-existed").entrySet(), hasSize(0));
    }

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
        RaceTimes times = new RaceTimes(
            "race-9",
            List.of("767", "765", "770"),
            "770",
            null,
            Map.of(
                "767", new RaceTimes.BoatTimes(true,  "18:13:02", "19:07:11"),
                "765", new RaceTimes.BoatTimes(true,  "18:10:00", "19:05:22"),
                "770", new RaceTimes.BoatTimes(false, null,       null)));
        first.putRaceTimes("race-9", times);

        JsonStore reopened = new JsonStore(tmp);
        reopened.start();
        RaceTimes read = reopened.raceTimes("race-9");
        assertThat(read.raceId(), equalTo("race-9"));
        assertThat(read.boatOrder(), contains("767", "765", "770"));
        assertThat(read.dutyBoatId(), equalTo("770"));
        assertThat(read.times().get("767").came(), is(true));
        assertThat(read.times().get("767").actualStart(), equalTo("18:13:02"));
        assertThat(read.times().get("767").finish(), equalTo("19:07:11"));
        assertThat(read.times().get("770").came(), is(false));
        assertThat(read.times().get("770").actualStart(), nullValue());
    }

    @Test
    void raceTimesDutyBoatNullablePersists(@TempDir Path tmp) throws IOException
    {
        JsonStore first = new JsonStore(tmp);
        first.start();
        first.putRaceTimes("race-10", new RaceTimes(
            "race-10", List.of("767"), null, null,
            Map.of("767", new RaceTimes.BoatTimes(true, "18:13:02", "19:07:11"))));

        JsonStore reopened = new JsonStore(tmp);
        reopened.start();
        assertThat(reopened.raceTimes("race-10").dutyBoatId(), nullValue());
    }

    @Test
    void raceTimesDivisionStartsRoundTrip(@TempDir Path tmp) throws IOException
    {
        // Non-pursuit races: the RO captures one actualStart per division
        // (not per boat). Stored alongside the per-boat times so a future
        // schema upgrade doesn't have to migrate two parallel files.
        JsonStore first = new JsonStore(tmp);
        first.start();
        first.putRaceTimes("race-11", new RaceTimes(
            "race-11", List.of("100", "200"), null,
            Map.of("13779", "14:00:05", "13780", "14:10:02"),
            Map.of(
                "100", new RaceTimes.BoatTimes(true, null, "15:21:00"),
                "200", new RaceTimes.BoatTimes(true, null, "15:33:14"))));

        JsonStore reopened = new JsonStore(tmp);
        reopened.start();
        RaceTimes read = reopened.raceTimes("race-11");
        assertThat(read.divisionStarts(), aMapWithSize(2));
        assertThat(read.divisionStarts(), hasEntry(is("13779"), is("14:00:05")));
        assertThat(read.divisionStarts(), hasEntry(is("13780"), is("14:10:02")));
    }

    @Test
    void seriesConfigIsNullWhenMissing(@TempDir Path tmp) throws IOException
    {
        JsonStore store = new JsonStore(tmp);
        store.start();
        assertThat(store.seriesConfig("5699"), nullValue());
    }

    @Test
    void seriesConfigRoundTripsAcrossRestart(@TempDir Path tmp) throws IOException
    {
        JsonStore first = new JsonStore(tmp);
        first.start();
        first.putSeriesConfig("5699",
            new JinxConfig.Algorithm(List.of(7.0, 5.0, 3.0, 1.0), 75, 4, "17:30",
                -34.5678, 150.4321, true));

        JsonStore reopened = new JsonStore(tmp);
        reopened.start();
        JinxConfig.Algorithm read = reopened.seriesConfig("5699");
        assertThat(read.penaltyList(), contains(7.0, 5.0, 3.0, 1.0));
        assertThat(read.idealRaceDuration(), equalTo(75));
        assertThat(read.dnfAllowance(), equalTo(4));
        assertThat(read.earliestStart(), equalTo("17:30"));
        assertThat(read.latitude(), equalTo(-34.5678));
        assertThat(read.longitude(), equalTo(150.4321));
        assertThat(read.limitBySunset(), is(true));
    }

    @Test
    void pendingAdjustmentsIsEmptyWhenMissing(@TempDir Path tmp) throws IOException
    {
        JsonStore store = new JsonStore(tmp);
        store.start();
        assertThat(store.pendingAdjustments("race-1"), hasSize(0));
    }

    @Test
    void pendingAdjustmentsRoundTripAcrossRestart(@TempDir Path tmp) throws IOException
    {
        JsonStore first = new JsonStore(tmp);
        first.start();
        first.putPendingAdjustments("race-7", List.of(
            new Adjustment("12345", 1, 5.0, 1.71, 3.29, 1.0450, 1.0666),
            new Adjustment("23456", 2, 4.0, 1.76, 2.24, 0.9340, 0.9485)));

        JsonStore reopened = new JsonStore(tmp);
        reopened.start();
        List<Adjustment> read = reopened.pendingAdjustments("race-7");
        assertThat(read, hasSize(2));
        assertThat(read.get(0).boatId(), equalTo("12345"));
        assertThat(read.get(0).newTcf(), equalTo(1.0666));
        assertThat(read.get(1).boatId(), equalTo("23456"));
        assertThat(read.get(1).netAdjustmentMinutes(), equalTo(2.24));
    }

    @Test
    void calibrationIsNullWhenMissing(@TempDir Path tmp) throws IOException
    {
        JsonStore store = new JsonStore(tmp);
        store.start();
        assertThat(store.calibration(), nullValue());
    }

    @Test
    void calibrationRoundTripsAcrossRestart(@TempDir Path tmp) throws IOException
    {
        JsonStore first = new JsonStore(tmp);
        first.start();
        Calibration cal = new Calibration(
            6.107, 100.0, 0.79, 0L, 1.12, 21960L,
            Instant.parse("2026-05-29T09:30:00Z"));
        first.putCalibration(cal);

        JsonStore reopened = new JsonStore(tmp);
        reopened.start();
        Calibration read = reopened.calibration();
        assertThat(read.v0Knots(), equalTo(6.107));
        assertThat(read.courseLengthNm(), equalTo(100.0));
        assertThat(read.slowestTcf(), equalTo(0.79));
        assertThat(read.fastestTcf(), equalTo(1.12));
        assertThat(read.fastestStartOffsetSeconds(), equalTo(21960L));
        assertThat(read.computedAt(), equalTo(Instant.parse("2026-05-29T09:30:00Z")));
    }

    @Test
    void auditAppendIsOrderedAndPersisted(@TempDir Path tmp) throws IOException
    {
        JsonStore first = new JsonStore(tmp);
        first.start();
        first.appendAudit(new AuditEntry(Instant.parse("2026-06-05T10:00:00Z"),
            "race-1", "process", 0.4, 15.0, List.of(), "first"));
        first.appendAudit(new AuditEntry(Instant.parse("2026-06-05T10:05:00Z"),
            "race-1", "push", 0.4, 15.0,
            List.of(new Adjustment("12345", 1, 5.0, 1.68, 3.32, 1.0450, 1.0666)),
            "second"));

        JsonStore reopened = new JsonStore(tmp);
        reopened.start();
        assertThat(reopened.audit(), hasSize(2));
        assertThat(reopened.audit().stream().map(AuditEntry::notes).toList(),
            contains("first", "second"));
        AuditEntry second = reopened.audit().get(1);
        assertThat(second.adjustments(), hasSize(1));
        assertThat(second.adjustments().get(0).newTcf(), equalTo(1.0666));
    }
}
