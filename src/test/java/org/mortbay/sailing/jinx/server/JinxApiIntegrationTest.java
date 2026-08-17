package org.mortbay.sailing.jinx.server;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.closeTo;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

/**
 * End-to-end exercise of the standalone API: boot the real server against a
 * temporary data root and drive a season through it over HTTP, exactly as the
 * browser does.
 *
 * <p>The point is not any single endpoint but the shape of the whole workflow
 * now that nothing external supplies data: register boats, build a roster, seed
 * entrants, compute the stagger, capture times, process handicaps, and watch
 * the new TCFs land on the next race.
 *
 * <p>Uses the JDK's own HTTP client. sail-jinx has no client dependency of its
 * own and must not acquire one.
 */
class JinxApiIntegrationTest
{
    private static final ObjectMapper M = new ObjectMapper();

    private Server server;
    private HttpClient http;
    private String base;
    private Path dataRoot;

    @BeforeEach
    void startServer(@TempDir Path tmp) throws Exception
    {
        dataRoot = tmp;
        Files.createDirectories(tmp.resolve("config"));
        Files.writeString(tmp.resolve("config/config.yaml"), """
            club:
              name: "Test Yacht Club"
              timezone: "Australia/Sydney"
            algorithm:
              penaltyList: [6, 4, 2]
              idealRaceDuration: 90
              dnfAllowance: 5
              earliestStart: "18:00"
              v0knots: 5.5
            server:
              port: 0
            """);

        server = JinxServer.start(tmp, 0);
        base = "http://localhost:" + localPort();
        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    private int localPort()
    {
        return ((ServerConnector)server.getConnectors()[0]).getLocalPort();
    }

    @AfterEach
    void stopServer() throws Exception
    {
        if (server != null)
            server.stop();
    }

    @Test
    void configReportsTheBuildAndAHealthyStore() throws Exception
    {
        JsonNode cfg = get("/api/config");
        assertThat(cfg.path("version").asText(), not(equalTo("")));
        assertThat(cfg.path("club").path("name").asText(), equalTo("Test Yacht Club"));
        assertThat(cfg.path("algorithm").path("v0knots").asDouble(), closeTo(5.5, 1e-9));
        assertThat(cfg.path("storeErrors").size(), equalTo(0));
    }

    @Test
    void aFreshInstallHasNothingInIt() throws Exception
    {
        assertThat(get("/api/boats").size(), equalTo(0));
        assertThat(get("/api/series").size(), equalTo(0));
        assertThat(get("/api/races").size(), equalTo(0));
    }

    @Test
    void boatsAreCreatedWithMintedIdsAndListedBySailNumber() throws Exception
    {
        post("/api/boats", """
            {"sailNumber":"AUS9","name":"Quick Silver","currentTcf":1.0450}""");
        post("/api/boats", """
            {"sailNumber":"A123","name":"Slow Poke","currentTcf":0.8821,"spinnaker":"NS"}""");

        JsonNode boats = get("/api/boats");
        assertThat(boats.size(), equalTo(2));
        assertThat(boats.get(0).path("sailNumber").asText(), equalTo("A123"));
        assertThat(boats.get(0).path("spinnaker").asText(), equalTo("NS"));
        assertThat(boats.get(0).path("id").asText(), not(equalTo("")));
        assertThat(boats.get(1).path("sailNumber").asText(), equalTo("AUS9"));
        // Defaults: a boat is active and not casual unless told otherwise.
        assertThat(boats.get(1).path("active").asBoolean(), is(true));
        assertThat(boats.get(1).path("casual").asBoolean(), is(false));
    }

    @Test
    void aBoatNeedsAtLeastASailNumberOrAName() throws Exception
    {
        HttpResponse<String> r = postRaw("/api/boats", "{\"currentTcf\":1.0}");
        assertThat(r.statusCode(), equalTo(400));
    }

    @Test
    void racesAreNumberedAutomaticallyWithinASeries() throws Exception
    {
        String seriesId = createSeries("2026 Winter Twilight");
        String r1 = createRace(seriesId, "2026-06-05");
        String r2 = createRace(seriesId, "2026-06-12");

        JsonNode races = get("/api/series/" + seriesId + "/races");
        assertThat(races.size(), equalTo(2));
        assertThat(races.get(0).path("id").asText(), equalTo(r1));
        assertThat(races.get(0).path("number").asInt(), equalTo(1));
        assertThat(races.get(1).path("id").asText(), equalTo(r2));
        assertThat(races.get(1).path("number").asInt(), equalTo(2));
    }

    @Test
    void aRaceNeedsAKnownSeries() throws Exception
    {
        HttpResponse<String> r = postRaw("/api/races",
            "{\"seriesId\":\"does-not-exist\",\"date\":\"2026-06-05\"}");
        assertThat(r.statusCode(), equalTo(400));
    }

    @Test
    void entrantsSeedFromTheRosterOnTheFirstRace() throws Exception
    {
        String seriesId = createSeries("2026 Winter Twilight");
        String fast = createBoat("AUS9", "Quick Silver", 1.0450);
        String slow = createBoat("A123", "Slow Poke", 0.8821);
        putRoster(seriesId, fast, 1.0450, slow, 0.8821);
        String raceId = createRace(seriesId, "2026-06-05");

        JsonNode seeded = post("/api/races/" + raceId + "/entrants/seed", "{}")
            .path("entrants");

        assertThat(seeded.path("tcfSource").asText(), equalTo("ROSTER"));
        assertThat(seeded.path("entrants").size(), equalTo(2));
        assertThat(seeded.path("entrants").get(0).path("sailNumber").asText(), equalTo("AUS9"));
        assertThat(seeded.path("entrants").get(0).path("tcf").asDouble(), closeTo(1.0450, 1e-9));
    }

    @Test
    void seedingRefusesToClobberAnExistingEntrantList() throws Exception
    {
        String seriesId = createSeries("S");
        String boatId = createBoat("AUS9", "Quick Silver", 1.0);
        putRoster(seriesId, boatId, 1.0);
        String raceId = createRace(seriesId, "2026-06-05");
        post("/api/races/" + raceId + "/entrants/seed", "{}");

        HttpResponse<String> again = postRaw("/api/races/" + raceId + "/entrants/seed", "{}");
        assertThat(again.statusCode(), equalTo(400));
    }

    @Test
    void startTimesStaggerTheFleetWithTheSlowestBoatOnTheEarliestGun() throws Exception
    {
        String seriesId = createSeries("2026 Winter Twilight");
        String fast = createBoat("AUS9", "Quick Silver", 1.0450);
        String mid = createBoat("5678", "Mid Fleet", 0.9450);
        String slow = createBoat("A123", "Slow Poke", 0.8821);
        putRoster(seriesId, fast, 1.0450, mid, 0.9450, slow, 0.8821);
        String raceId = createRace(seriesId, "2026-06-05");
        post("/api/races/" + raceId + "/entrants/seed", "{}");

        JsonNode sheet = post("/api/races/" + raceId + "/start-times",
            "{\"targetElapsedMinutes\":90,\"earliestStart\":\"18:00\"}").path("startSheet");

        assertThat(sheet.path("targetElapsedMinutes").asInt(), equalTo(90));
        JsonNode starts = sheet.path("starts");
        assertThat(starts.size(), equalTo(3));
        // Ordered slowest first — the order they start, and the order the
        // start-offset report prints.
        assertThat(starts.get(0).path("boatId").asText(), equalTo(slow));
        assertThat(starts.get(0).path("startTime").asText(), equalTo("18:00:00"));
        assertThat(starts.get(2).path("boatId").asText(), equalTo(fast));
        assertThat(starts.get(2).path("startTime").asText(), greaterThan("18:00:00"));
    }

    @Test
    void startTimesNeedEntrants() throws Exception
    {
        String seriesId = createSeries("S");
        String raceId = createRace(seriesId, "2026-06-05");

        HttpResponse<String> r = postRaw("/api/races/" + raceId + "/start-times", "{}");
        assertThat(r.statusCode(), equalTo(400));
    }

    @Test
    void coursePlanSizesTheCourseFromTheSlowestEntrant() throws Exception
    {
        String seriesId = createSeries("S");
        String fast = createBoat("AUS9", "Quick Silver", 1.0450);
        String slow = createBoat("A123", "Slow Poke", 0.8000);
        putRoster(seriesId, fast, 1.0450, slow, 0.8000);
        String raceId = createRace(seriesId, "2026-06-05");
        post("/api/races/" + raceId + "/entrants/seed", "{}");

        JsonNode plan = post("/api/races/" + raceId + "/course-plan",
            "{\"targetElapsedMinutes\":90}");

        assertThat(plan.path("slowestTcf").asDouble(), closeTo(0.8000, 1e-9));
        // 0.8 × 5.5 × 1.5 h = 6.6 nm
        assertThat(plan.path("courseLengthNm").asDouble(), closeTo(6.6, 1e-9));
        assertThat(plan.path("limitedBySunset").asBoolean(), is(false));
    }

    @Test
    void capturedTimesRoundTrip() throws Exception
    {
        String seriesId = createSeries("S");
        String raceId = createRace(seriesId, "2026-06-05");

        post("/api/races/" + raceId + "/times", """
            {"boatOrder":["b-1","b-2"],"dutyBoatId":"b-2",
             "times":{"b-1":{"came":true,"actualStart":"18:13:00","finish":"19:44:20"},
                      "b-2":{"came":false,"actualStart":null,"finish":null}}}""");

        JsonNode times = get("/api/races/" + raceId + "/times").path("times");
        assertThat(times.path("dutyBoatId").asText(), equalTo("b-2"));
        assertThat(times.path("times").path("b-1").path("finish").asText(), equalTo("19:44:20"));
        assertThat(times.path("times").path("b-2").path("came").asBoolean(), is(false));
    }

    @Test
    void aOneOffEntrantNeedsNoRegisterBoat() throws Exception
    {
        String seriesId = createSeries("S");
        String boatId = createBoat("AUS9", "Quick Silver", 1.0);
        putRoster(seriesId, boatId, 1.0);
        String raceId = createRace(seriesId, "2026-06-05");
        post("/api/races/" + raceId + "/entrants/seed", "{}");

        post("/api/races/" + raceId + "/entrants", """
            {"entrants":[
               {"boatId":"%s","tcf":1.0},
               {"sailNumber":"??? 42","name":"Visitor","tcf":0.95}
             ]}""".formatted(boatId));

        JsonNode entrants = get("/api/races/" + raceId).path("entrants").path("entrants");
        assertThat(entrants.size(), equalTo(2));
        assertThat(entrants.get(1).path("entryType").asText(), equalTo("ONE_OFF"));
        assertThat(entrants.get(1).path("boatId").isMissingNode(), is(true));
        assertThat(entrants.get(1).path("name").asText(), equalTo("Visitor"));
    }

    @Test
    void anEntrantReferencingAnUnknownBoatIsRejected() throws Exception
    {
        String seriesId = createSeries("S");
        String raceId = createRace(seriesId, "2026-06-05");

        HttpResponse<String> r = postRaw("/api/races/" + raceId + "/entrants",
            "{\"entrants\":[{\"boatId\":\"b-nope\",\"tcf\":1.0}]}");
        assertThat(r.statusCode(), equalTo(400));
    }

    /**
     * The whole point of the application: process race 1's results and watch
     * the adjusted TCFs arrive on race 2, with no external system involved.
     */
    @Test
    void processingHandicapsCarriesNewTcfsToTheNextRace() throws Exception
    {
        String seriesId = createSeries("2026 Winter Twilight");
        String fast = createBoat("AUS9", "Quick Silver", 1.0000);
        String mid = createBoat("5678", "Mid Fleet", 1.0000);
        String slow = createBoat("A123", "Slow Poke", 1.0000);
        putRoster(seriesId, fast, 1.0, mid, 1.0, slow, 1.0);
        String race1 = createRace(seriesId, "2026-06-05");
        String race2 = createRace(seriesId, "2026-06-12");
        post("/api/races/" + race1 + "/entrants/seed", "{}");

        JsonNode processed = post("/api/races/" + race1 + "/process-handicaps", """
            {"targetElapsedMinutes":90,
             "boats":[
               {"boatId":"%s","currentTcf":1.0,"status":"FIN","elapsedMinutes":88.0,"finishPosition":1},
               {"boatId":"%s","currentTcf":1.0,"status":"FIN","elapsedMinutes":92.0,"finishPosition":2},
               {"boatId":"%s","currentTcf":1.0,"status":"FIN","elapsedMinutes":96.0,"finishPosition":3}
             ]}""".formatted(fast, mid, slow));

        JsonNode adjustments = processed.path("adjustments");
        assertThat(adjustments.size(), equalTo(3));
        // The winner is penalised, so its TCF rises and it starts later next time.
        JsonNode winner = adjustments.get(0);
        assertThat(winner.path("boatId").asText(), equalTo(fast));
        assertThat(winner.path("penaltyMinutes").asDouble(), closeTo(6.0, 1e-9));
        assertThat(winner.path("newTcf").asDouble(), greaterThan(1.0));

        // Nothing is saved until Save Handicaps, so race 1 is still unlocked.
        assertThat(get("/api/races/" + race1).path("locked").asBoolean(), is(false));

        JsonNode saved = post("/api/races/" + race1 + "/save-handicaps",
            "{\"adjustments\":" + adjustments + "}");
        assertThat(saved.path("nextRaceId").asText(), equalTo(race2));
        assertThat(saved.path("carriedEntrants").asInt(), equalTo(3));

        // Race 2 now carries the new TCFs, attributed to race 1.
        JsonNode race2Entrants = get("/api/races/" + race2).path("entrants");
        assertThat(race2Entrants.path("tcfSource").asText(), equalTo("CARRIED_FORWARD"));
        assertThat(race2Entrants.path("sourceRaceId").asText(), equalTo(race1));
        assertThat(race2Entrants.path("sourceRaceNumber").asInt(), equalTo(1));

        double carriedWinnerTcf = -1;
        for (JsonNode e : race2Entrants.path("entrants"))
        {
            if (fast.equals(e.path("boatId").asText()))
                carriedWinnerTcf = e.path("tcf").asDouble();
        }
        assertThat(carriedWinnerTcf, closeTo(winner.path("newTcf").asDouble(), 1e-12));

        // Race 1's own entrants keep the TCFs it was actually sailed on. This is
        // the history SailSys could never keep.
        for (JsonNode e : get("/api/races/" + race1).path("entrants").path("entrants"))
            assertThat(e.path("tcf").asDouble(), closeTo(1.0, 1e-12));
    }

    @Test
    void savingHandicapsLocksTheRaceAndUnlockingReleasesIt() throws Exception
    {
        String seriesId = createSeries("S");
        String boatId = createBoat("AUS9", "Quick Silver", 1.0);
        putRoster(seriesId, boatId, 1.0);
        String raceId = createRace(seriesId, "2026-06-05");
        post("/api/races/" + raceId + "/entrants/seed", "{}");

        post("/api/races/" + raceId + "/save-handicaps", """
            {"adjustments":[{"boatId":"%s","finishPosition":1,"penaltyMinutes":6.0,
              "rewardMinutes":6.0,"netAdjustmentMinutes":0.0,"oldTcf":1.0,"newTcf":1.0}]}"""
            .formatted(boatId));

        assertThat(get("/api/races/" + raceId).path("locked").asBoolean(), is(true));

        // A locked race refuses edits to the data its handicaps were computed from.
        HttpResponse<String> times = postRaw("/api/races/" + raceId + "/times",
            "{\"boatOrder\":[],\"times\":{}}");
        assertThat(times.statusCode(), equalTo(409));

        JsonNode unlocked = delete("/api/races/" + raceId + "/adjustments");
        assertThat(unlocked.path("unlocked").asBoolean(), is(true));
        assertThat(get("/api/races/" + raceId).path("locked").asBoolean(), is(false));

        // And now it takes edits again.
        assertThat(postRaw("/api/races/" + raceId + "/times",
            "{\"boatOrder\":[],\"times\":{}}").statusCode(), equalTo(200));
    }

    @Test
    void theRaceBundleCarriesEverythingThePageNeeds() throws Exception
    {
        String seriesId = createSeries("2026 Winter Twilight");
        String boatId = createBoat("AUS9", "Quick Silver", 1.0);
        putRoster(seriesId, boatId, 1.0);
        String race1 = createRace(seriesId, "2026-06-05");
        String race2 = createRace(seriesId, "2026-06-12");
        post("/api/races/" + race1 + "/entrants/seed", "{}");
        post("/api/races/" + race1 + "/start-times", "{}");

        JsonNode bundle = get("/api/races/" + race1);
        assertThat(bundle.path("race").path("id").asText(), equalTo(race1));
        assertThat(bundle.path("seriesName").asText(), equalTo("2026 Winter Twilight"));
        assertThat(bundle.path("algorithm").path("v0knots").asDouble(), closeTo(5.5, 1e-9));
        assertThat(bundle.path("entrants").path("entrants").size(), equalTo(1));
        assertThat(bundle.path("startSheet").path("starts").size(), equalTo(1));
        assertThat(bundle.path("locked").asBoolean(), is(false));
        assertThat(bundle.path("nextRaceId").asText(), equalTo(race2));
        assertThat(bundle.path("previousRaceId").isNull(), is(true));

        assertThat(get("/api/races/" + race2).path("previousRaceId").asText(), equalTo(race1));
    }

    @Test
    void anUnknownRaceIsA404() throws Exception
    {
        assertThat(getRaw("/api/races/r-nope").statusCode(), equalTo(404));
    }

    @Test
    void savingHandicapsIsAudited() throws Exception
    {
        String seriesId = createSeries("S");
        String boatId = createBoat("AUS9", "Quick Silver", 1.0);
        putRoster(seriesId, boatId, 1.0);
        String raceId = createRace(seriesId, "2026-06-05");
        post("/api/races/" + raceId + "/entrants/seed", "{}");
        post("/api/races/" + raceId + "/save-handicaps", """
            {"adjustments":[{"boatId":"%s","finishPosition":1,"penaltyMinutes":6.0,
              "rewardMinutes":6.0,"netAdjustmentMinutes":0.0,"oldTcf":1.0,"newTcf":1.02}]}"""
            .formatted(boatId));

        JsonNode audit = get("/api/audit");
        assertThat(audit.size(), equalTo(1));
        assertThat(audit.get(0).path("action").asText(), equalTo("save-handicaps"));
        assertThat(audit.get(0).path("raceId").asText(), equalTo(raceId));
        assertThat(audit.get(0).path("adjustments").size(), equalTo(1));
    }

    @Test
    void perSeriesAlgorithmSettingsOverrideTheDefaults() throws Exception
    {
        String seriesId = createSeries("S");

        JsonNode before = get("/api/series/" + seriesId + "/config");
        assertThat(before.path("isCustom").asBoolean(), is(false));
        assertThat(before.path("config").path("idealRaceDuration").asInt(), equalTo(90));

        post("/api/series/" + seriesId + "/config",
            "{\"penaltyList\":[10,5],\"idealRaceDuration\":60,\"v0knots\":6.5}");

        JsonNode after = get("/api/series/" + seriesId + "/config");
        assertThat(after.path("isCustom").asBoolean(), is(true));
        assertThat(after.path("config").path("idealRaceDuration").asInt(), equalTo(60));
        assertThat(after.path("config").path("v0knots").asDouble(), closeTo(6.5, 1e-9));
        // Defaults still travel alongside so the form can offer "restore".
        assertThat(after.path("defaults").path("idealRaceDuration").asInt(), equalTo(90));

        // And the override reaches the course calculator for this series' races.
        String boatId = createBoat("AUS9", "Quick Silver", 1.0);
        putRoster(seriesId, boatId, 1.0);
        String raceId = createRace(seriesId, "2026-06-05");
        post("/api/races/" + raceId + "/entrants/seed", "{}");
        JsonNode plan = post("/api/races/" + raceId + "/course-plan", "{}");
        assertThat(plan.path("v0knots").asDouble(), closeTo(6.5, 1e-9));
        assertThat(plan.path("requestedDurationMinutes").asInt(), equalTo(60));
    }

    @Test
    void theRosterJoinsToTheRegister() throws Exception
    {
        String seriesId = createSeries("S");
        String boatId = createBoat("AUS9", "Quick Silver", 1.0450);
        putRoster(seriesId, boatId, 0.9900);

        JsonNode roster = get("/api/series/" + seriesId + "/roster");
        assertThat(roster.path("entries").size(), equalTo(1));
        JsonNode row = roster.path("entries").get(0);
        assertThat(row.path("sailNumber").asText(), equalTo("AUS9"));
        assertThat(row.path("name").asText(), equalTo("Quick Silver"));
        // The roster's starting TCF wins over the register's seed value.
        assertThat(row.path("startingTcf").asDouble(), closeTo(0.9900, 1e-9));
    }

    @Test
    void aRosterEntryWithoutATcfTakesTheRegisterSeed() throws Exception
    {
        String seriesId = createSeries("S");
        String boatId = createBoat("AUS9", "Quick Silver", 1.0450);
        post("/api/series/" + seriesId + "/roster",
            "{\"entries\":[{\"boatId\":\"" + boatId + "\"}]}");

        JsonNode roster = get("/api/series/" + seriesId + "/roster");
        assertThat(roster.path("entries").get(0).path("startingTcf").asDouble(),
            closeTo(1.0450, 1e-9));
    }

    @Test
    void everythingSurvivesARestart() throws Exception
    {
        String seriesId = createSeries("2026 Winter Twilight");
        String boatId = createBoat("AUS9", "Quick Silver", 1.0450);
        putRoster(seriesId, boatId, 1.0450);
        String raceId = createRace(seriesId, "2026-06-05");
        post("/api/races/" + raceId + "/entrants/seed", "{}");
        post("/api/races/" + raceId + "/start-times", "{}");
        post("/api/races/" + raceId + "/times", """
            {"boatOrder":["%s"],"times":{"%s":{"came":true,"actualStart":"18:00:00",
              "finish":"19:30:00"}}}""".formatted(boatId, boatId));

        // Restart against the same data root — this is the only copy, so it had
        // better all still be there.
        server.stop();
        server = JinxServer.start(dataRoot, 0);
        base = "http://localhost:" + localPort();

        assertThat(get("/api/config").path("storeErrors").size(), equalTo(0));
        assertThat(get("/api/boats").size(), equalTo(1));
        assertThat(get("/api/series").size(), equalTo(1));
        JsonNode bundle = get("/api/races/" + raceId);
        assertThat(bundle.path("entrants").path("entrants").size(), equalTo(1));
        assertThat(bundle.path("startSheet").path("starts").size(), equalTo(1));
        assertThat(bundle.path("times").path("times").path(boatId).path("finish").asText(),
            equalTo("19:30:00"));
    }

    // --- helpers -------------------------------------------------------------

    private String createSeries(String name) throws Exception
    {
        return post("/api/series", "{\"name\":\"" + name + "\"}")
            .path("series").path("id").asText();
    }

    private String createBoat(String sail, String name, double tcf) throws Exception
    {
        return post("/api/boats", """
            {"sailNumber":"%s","name":"%s","currentTcf":%s}"""
            .formatted(sail, name, tcf)).path("boat").path("id").asText();
    }

    private String createRace(String seriesId, String date) throws Exception
    {
        return post("/api/races", """
            {"seriesId":"%s","date":"%s"}""".formatted(seriesId, date))
            .path("race").path("id").asText();
    }

    /** {@code putRoster(seriesId, boatId, tcf, boatId, tcf, ...)}. */
    private void putRoster(String seriesId, Object... boatIdsAndTcfs) throws Exception
    {
        StringBuilder sb = new StringBuilder("{\"entries\":[");
        for (int i = 0; i + 1 < boatIdsAndTcfs.length; i += 2)
        {
            if (i > 0) sb.append(',');
            sb.append("{\"boatId\":\"").append(boatIdsAndTcfs[i])
                .append("\",\"startingTcf\":").append(boatIdsAndTcfs[i + 1]).append('}');
        }
        sb.append("]}");
        post("/api/series/" + seriesId + "/roster", sb.toString());
    }

    private JsonNode get(String path) throws Exception
    {
        HttpResponse<String> r = getRaw(path);
        assertThat("GET " + path + " -> " + r.body(), r.statusCode(), equalTo(200));
        return M.readTree(r.body());
    }

    private HttpResponse<String> getRaw(String path) throws Exception
    {
        return http.send(HttpRequest.newBuilder(URI.create(base + path)).GET().build(),
            HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode post(String path, String body) throws Exception
    {
        HttpResponse<String> r = postRaw(path, body);
        assertThat("POST " + path + " -> " + r.body(), r.statusCode(), equalTo(200));
        return M.readTree(r.body());
    }

    private HttpResponse<String> postRaw(String path, String body) throws Exception
    {
        return http.send(HttpRequest.newBuilder(URI.create(base + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
            HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode delete(String path) throws Exception
    {
        HttpResponse<String> r = http.send(
            HttpRequest.newBuilder(URI.create(base + path)).DELETE().build(),
            HttpResponse.BodyHandlers.ofString());
        assertThat("DELETE " + path + " -> " + r.body(), r.statusCode(), equalTo(200));
        return M.readTree(r.body());
    }
}
