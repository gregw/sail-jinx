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
import org.mortbay.sailing.jinx.model.Adjustment;
import org.mortbay.sailing.jinx.model.AuditEntry;
import org.mortbay.sailing.jinx.model.Boat;
import org.mortbay.sailing.jinx.model.FinishStatus;
import org.mortbay.sailing.jinx.model.Race;
import org.mortbay.sailing.jinx.model.RaceStatus;
import org.mortbay.sailing.jinx.model.Result;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.aMapWithSize;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasEntry;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;

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
