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
import org.mortbay.sailing.jinx.model.Tcf;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.closeTo;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.lessThan;
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
              domain: "test.org.au"
              shortName: "TYC"
              longName: "Test Yacht Club"
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
        assertThat(cfg.path("club").path("domain").asText(), equalTo("test.org.au"));
        assertThat(cfg.path("club").path("longName").asText(), equalTo("Test Yacht Club"));
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
    void boatsAreCreatedWithReadableIdsAndListedBySailNumber() throws Exception
    {
        post("/api/boats", """
            {"sailNumber":"AUS9","name":"Quick Silver","design":"J/24"}""");
        post("/api/boats", """
            {"sailNumber":"A123","name":"Slow Poke"}""");

        JsonNode boats = get("/api/boats");
        assertThat(boats.size(), equalTo(2));
        // AUS9 canonicalises to its bare form — the country prefix is normalisation, not
        // identity — so it sorts before A123.
        assertThat(boats.get(0).path("sailNumber").asText(), equalTo("9"));
        // No alias seed in this test's config, so the design id is the plain
        // normalisation of what was typed. With the shipped seed it would resolve
        // further, to jboatsj24.
        assertThat(boats.get(0).path("id").asText(), equalTo("9-quicksilver-j24"));
        assertThat(boats.get(1).path("sailNumber").asText(), equalTo("A123"));
        // Defaults: a boat is active and not casual unless told otherwise.
        assertThat(boats.get(0).path("active").asBoolean(), is(true));
        assertThat(boats.get(0).path("casual").asBoolean(), is(false));
    }

    @Test
    void theRegisterHoldsNoHandicapDivisionOrSpinnaker() throws Exception
    {
        // None of them is a property of a hull. A boat has a handicap for a series, and
        // a different one by the end of it; it can sail one season in Division 1 and the
        // next in Division 2, with or without a kite.
        post("/api/boats", """
            {"sailNumber":"AUS9","name":"Quick Silver","currentTcf":1.0450,
             "division":"Div 1","spinnaker":"NS"}""");

        JsonNode boat = get("/api/boats").get(0);
        assertThat(boat.has("currentTcf"), is(false));
        assertThat(boat.has("division"), is(false));
        assertThat(boat.has("spinnaker"), is(false));
    }

    @Test
    void theSameBoatCanEnterTwoSeriesOnDifferentTerms() throws Exception
    {
        String boatId = createBoat("AUS9", "Quick Silver");
        String summer = createSeries("Summer");
        String winter = createSeries("Winter");

        post("/api/series/" + summer + "/roster",
            "{\"entries\":[{\"boatId\":\"" + boatId + "\",\"startingTcf\":1.0450,"
            + "\"division\":\"Div 1\",\"spinnaker\":\"S\"}]}");
        post("/api/series/" + winter + "/roster",
            "{\"entries\":[{\"boatId\":\"" + boatId + "\",\"startingTcf\":0.9800,"
            + "\"division\":\"Div 2\",\"spinnaker\":\"NS\"}]}");

        JsonNode s = get("/api/series/" + summer + "/roster").path("entries").get(0);
        JsonNode w = get("/api/series/" + winter + "/roster").path("entries").get(0);

        assertThat(s.path("startingTcf").asDouble(), closeTo(1.0450, 1e-9));
        assertThat(w.path("startingTcf").asDouble(), closeTo(0.9800, 1e-9));
        assertThat(s.path("division").asText(), equalTo("Div 1"));
        assertThat(w.path("division").asText(), equalTo("Div 2"));
        assertThat(s.path("spinnaker").asText(), equalTo("S"));
        assertThat(w.path("spinnaker").asText(), equalTo("NS"));
        // One hull, one register record.
        assertThat(get("/api/boats").size(), equalTo(1));
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
        String fast = createBoat("AUS9", "Quick Silver");
        String slow = createBoat("A123", "Slow Poke");
        putRoster(seriesId, fast, 1.0450, slow, 0.8821);
        String raceId = createRace(seriesId, "2026-06-05");

        JsonNode seeded = post("/api/races/" + raceId + "/entrants/seed", "{}")
            .path("entrants");

        assertThat(seeded.path("tcfSource").asText(), equalTo("ROSTER"));
        assertThat(seeded.path("entrants").size(), equalTo(2));
        assertThat(seeded.path("entrants").get(0).path("sailNumber").asText(), equalTo("9"));
        assertThat(seeded.path("entrants").get(0).path("tcf").asDouble(), closeTo(1.0450, 1e-9));
    }

    @Test
    void seedingRefusesToClobberAnExistingEntrantList() throws Exception
    {
        String seriesId = createSeries("S");
        String boatId = createBoat("AUS9", "Quick Silver");
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
        String fast = createBoat("AUS9", "Quick Silver");
        String mid = createBoat("5678", "Mid Fleet");
        String slow = createBoat("A123", "Slow Poke");
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
        String boatId = createBoat("AUS9", "Quick Silver");
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
        String fast = createBoat("AUS9", "Quick Silver");
        String mid = createBoat("5678", "Mid Fleet");
        String slow = createBoat("A123", "Slow Poke");
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
        // The engine works in full precision; what gets recorded — and read out
        // and retyped — is four decimals. See Tcf.
        assertThat(carriedWinnerTcf, equalTo(Tcf.round(winner.path("newTcf").asDouble())));
        assertThat(Tcf.format(carriedWinnerTcf).length(), equalTo(6)); // "1.0234"

        // Race 1's own entrants keep the TCFs it was actually sailed on. This is
        // the history SailSys could never keep.
        for (JsonNode e : get("/api/races/" + race1).path("entrants").path("entrants"))
            assertThat(e.path("tcf").asDouble(), closeTo(1.0, 1e-12));
    }

    @Test
    void savingHandicapsLocksTheRaceAndUnlockingReleasesIt() throws Exception
    {
        String seriesId = createSeries("S");
        String boatId = createBoat("AUS9", "Quick Silver");
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
        String boatId = createBoat("AUS9", "Quick Silver");
        putRoster(seriesId, boatId, 1.0);
        String race1 = createRace(seriesId, "2026-06-05");
        String race2 = createRace(seriesId, "2026-06-12");
        post("/api/races/" + race1 + "/entrants/seed", "{}");
        post("/api/races/" + race1 + "/start-times", "{}");

        JsonNode bundle = get("/api/races/" + race1);
        assertThat(bundle.path("race").path("id").asText(), equalTo(race1));
        assertThat(bundle.path("seriesName").asText(), equalTo("2026 Winter Twilight"));
        // The race page shows the Spin column only for a mixed series, so the policy has
        // to travel with the bundle — there is nowhere else the page could learn it.
        assertThat(bundle.path("spinnakerPolicy").asText(), equalTo("MIXED"));
        assertThat(bundle.path("algorithm").path("v0knots").asDouble(), closeTo(5.5, 1e-9));
        assertThat(bundle.path("entrants").path("entrants").size(), equalTo(1));
        assertThat(bundle.path("startSheet").path("starts").size(), equalTo(1));
        assertThat(bundle.path("locked").asBoolean(), is(false));
        assertThat(bundle.path("nextRaceId").asText(), equalTo(race2));
        assertThat(bundle.path("previousRaceId").isNull(), is(true));

        assertThat(get("/api/races/" + race2).path("previousRaceId").asText(), equalTo(race1));
    }

    @Test
    void aSeriesCanBeEditedAndKeepsItsIdAcrossARename() throws Exception
    {
        String seriesId = createSeries("2026 Winter Twilight");
        String raceId = createRace(seriesId, "2026-06-05");

        JsonNode saved = post("/api/series", """
            {"id":"%s","name":"2026 Summer Twilight","spinnakerPolicy":"NON_SPINNAKER",\
             "raceFormat":"PURSUIT","handicapAlgorithm":"JINX","archived":false}"""
            .formatted(seriesId));

        // The id is minted from the name, so a rename would mint a different one — and
        // every race, roster and series-config file keys off the old one. An edit keeps
        // the id it was given; that is what makes the series editable at all.
        assertThat(saved.path("series").path("id").asText(), equalTo(seriesId));
        assertThat(saved.path("series").path("name").asText(), equalTo("2026 Summer Twilight"));
        assertThat(saved.path("series").path("spinnakerPolicy").asText(),
            equalTo("NON_SPINNAKER"));

        // One series, not two, and the race still belongs to it.
        assertThat(get("/api/series").size(), equalTo(1));
        assertThat(get("/api/races/" + raceId).path("race").path("seriesId").asText(),
            equalTo(seriesId));
        assertThat(get("/api/races/" + raceId).path("seriesName").asText(),
            equalTo("2026 Summer Twilight"));
        assertThat(get("/api/races/" + raceId).path("spinnakerPolicy").asText(),
            equalTo("NON_SPINNAKER"));
    }

    @Test
    void anEditCannotSelectAnUnimplementedFormatOrAlgorithm() throws Exception
    {
        String seriesId = createSeries("2026 Winter Twilight");

        HttpResponse<String> phs = postRaw("/api/series", """
            {"id":"%s","name":"2026 Winter Twilight","raceFormat":"PHS"}"""
            .formatted(seriesId));
        assertThat(phs.statusCode(), equalTo(400));

        HttpResponse<String> scratch = postRaw("/api/series", """
            {"id":"%s","name":"2026 Winter Twilight","handicapAlgorithm":"SCRATCH"}"""
            .formatted(seriesId));
        assertThat(scratch.statusCode(), equalTo(400));

        // Refused, so nothing was written: the series still reads as it did.
        assertThat(get("/api/series").get(0).path("raceFormat").asText(), equalTo("PURSUIT"));
        assertThat(get("/api/series").get(0).path("handicapAlgorithm").asText(),
            equalTo("JINX"));
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
        String boatId = createBoat("AUS9", "Quick Silver");
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
        String boatId = createBoat("AUS9", "Quick Silver");
        putRoster(seriesId, boatId, 1.0);
        String raceId = createRace(seriesId, "2026-06-05");
        post("/api/races/" + raceId + "/entrants/seed", "{}");
        JsonNode sheet = post("/api/races/" + raceId + "/start-times", "{}").path("startSheet");
        assertThat(sheet.path("targetElapsedMinutes").asInt(), equalTo(60));
    }

    @Test
    void theRosterJoinsToTheRegister() throws Exception
    {
        String seriesId = createSeries("S");
        String boatId = createBoat("AUS9", "Quick Silver");
        putRoster(seriesId, boatId, 0.9900);

        JsonNode roster = get("/api/series/" + seriesId + "/roster");
        assertThat(roster.path("entries").size(), equalTo(1));
        JsonNode row = roster.path("entries").get(0);
        assertThat(row.path("sailNumber").asText(), equalTo("9"));
        assertThat(row.path("name").asText(), equalTo("Quick Silver"));
        // The roster's starting TCF wins over the register's seed value.
        assertThat(row.path("startingTcf").asDouble(), closeTo(0.9900, 1e-9));
    }

    @Test
    void aRosterEntryWithoutATcfStartsOnScratch() throws Exception
    {
        // There is no "the boat's TCF" to inherit — a handicap belongs to the entry. 1.0
        // is visibly a starting point rather than a considered figure.
        String seriesId = createSeries("S");
        String boatId = createBoat("AUS9", "Quick Silver");
        post("/api/series/" + seriesId + "/roster",
            "{\"entries\":[{\"boatId\":\"" + boatId + "\"}]}");

        JsonNode roster = get("/api/series/" + seriesId + "/roster");
        assertThat(roster.path("entries").get(0).path("startingTcf").asDouble(),
            closeTo(1.0, 1e-9));
    }

    @Test
    void everythingSurvivesARestart() throws Exception
    {
        String seriesId = createSeries("2026 Winter Twilight");
        String boatId = createBoat("AUS9", "Quick Silver");
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

    @Test
    void aCasualIsNotSeededIntoTheNextRace() throws Exception
    {
        // It sailed tonight and its handicap moves like anyone else's, but it turned up
        // once — seeding it would put a boat nobody expects on next week's start sheet.
        String seriesId = createSeries("Twilight");
        String rostered = createBoat("AUS9", "Quick Silver");
        putRoster(seriesId, rostered, 1.0);
        String race1 = createRace(seriesId, "2026-06-05");
        String race2 = createRace(seriesId, "2026-06-12");
        post("/api/races/" + race1 + "/entrants/seed", "{}");

        String visitor = createBoat("MYC99", "Passing Through");
        post("/api/races/" + race1 + "/entrants", """
            {"entrants":[{"boatId":"%s","tcf":1.0,"entryType":"ROSTER"},
                         {"boatId":"%s","tcf":0.95,"entryType":"CASUAL"}]}"""
            .formatted(rostered, visitor));
        assertThat(get("/api/races/" + race1).path("entrants").path("entrants").size(),
            equalTo(2));

        // Both are scored; only the roster boat is carried.
        post("/api/races/" + race1 + "/save-handicaps", """
            {"adjustments":[
              {"boatId":"%s","finishPosition":1,"penaltyMinutes":6.0,"rewardMinutes":3.0,
               "netAdjustmentMinutes":3.0,"oldTcf":1.0,"newTcf":1.03},
              {"boatId":"%s","finishPosition":2,"penaltyMinutes":4.0,"rewardMinutes":7.0,
               "netAdjustmentMinutes":-3.0,"oldTcf":0.95,"newTcf":0.92}]}"""
            .formatted(rostered, visitor));

        JsonNode next = get("/api/races/" + race2).path("entrants").path("entrants");
        assertThat(next.size(), equalTo(1));
        assertThat(next.get(0).path("boatId").asText(), equalTo(rostered));
    }

    @Test
    void aCasualIsNotCarriedBySeedingFromThePreviousRaceEither() throws Exception
    {
        String seriesId = createSeries("Twilight");
        String rostered = createBoat("AUS9", "Quick Silver");
        String visitor = createBoat("MYC99", "Passing Through");
        putRoster(seriesId, rostered, 1.0);
        String race1 = createRace(seriesId, "2026-06-05");
        String race2 = createRace(seriesId, "2026-06-12");
        post("/api/races/" + race1 + "/entrants/seed", "{}");
        post("/api/races/" + race1 + "/entrants", """
            {"entrants":[{"boatId":"%s","tcf":1.0,"entryType":"ROSTER"},
                         {"boatId":"%s","tcf":0.95,"entryType":"CASUAL"}]}"""
            .formatted(rostered, visitor));

        JsonNode seeded = post("/api/races/" + race2 + "/entrants/seed", "{}").path("entrants");
        assertThat(seeded.path("entrants").size(), equalTo(1));
        assertThat(seeded.path("entrants").get(0).path("boatId").asText(), equalTo(rostered));
    }

    @Test
    void startTimesReportWhetherSunsetCappedTheTarget() throws Exception
    {
        String seriesId = createSeries("Twilight");
        String boatId = createBoat("AUS9", "Quick Silver");
        putRoster(seriesId, boatId, 1.0);
        // A summer date, so there is daylight after 18:00 to be capped into. A winter
        // one has none at all and is refused instead — see the test below.
        String raceId = createRace(seriesId, "2026-01-15");
        post("/api/races/" + raceId + "/entrants/seed", "{}");

        // Off by default: an unasked-for cap would silently shorten races.
        JsonNode uncapped = post("/api/races/" + raceId + "/start-times",
            "{\"targetElapsedMinutes\":300,\"earliestStart\":\"18:00\"}");
        assertThat(uncapped.path("limitedBySunset").asBoolean(), is(false));
        assertThat(uncapped.path("startSheet").path("targetElapsedMinutes").asInt(), equalTo(300));

        post("/api/series/" + seriesPath(seriesId) + "/config",
            "{\"limitBySunset\":true,\"latitude\":-33.8,\"longitude\":151.2833}");

        // Five hours from 18:00 is well past a Sydney June sunset, so it is cut back.
        JsonNode capped = post("/api/races/" + raceId + "/start-times",
            "{\"targetElapsedMinutes\":300,\"earliestStart\":\"18:00\"}");
        assertThat(capped.path("limitedBySunset").asBoolean(), is(true));
        assertThat(capped.path("sunsetLocal").asText().isEmpty(), is(false));
        assertThat(capped.path("startSheet").path("targetElapsedMinutes").asInt(),
            lessThan(300));
    }

    @Test
    void aDateWithNoDaylightAfterTheStartIsRefused() throws Exception
    {
        // Capping to nought minutes would produce a start sheet with every boat on the
        // same gun. The race as described cannot be sailed, so say so.
        String seriesId = createSeries("Twilight");
        String boatId = createBoat("AUS9", "Quick Silver");
        putRoster(seriesId, boatId, 1.0);
        post("/api/series/" + seriesPath(seriesId) + "/config",
            "{\"limitBySunset\":true,\"latitude\":-33.8,\"longitude\":151.2833}");
        String raceId = createRace(seriesId, "2026-06-21");
        post("/api/races/" + raceId + "/entrants/seed", "{}");

        HttpResponse<String> r = postRaw("/api/races/" + raceId + "/start-times",
            "{\"targetElapsedMinutes\":90,\"earliestStart\":\"18:00\"}");
        assertThat(r.statusCode(), equalTo(400));
        assertThat(r.body(), containsString("no daylight"));
    }

    // --- fleet import (sailing-pf export) ------------------------------------

    /** The shape sailing-pf's handicaps-YYYY-MM-DD.json files come in. */
    private static String export(String... rows)
    {
        return "[" + String.join(",", rows) + "]";
    }

    private static String boatRow(String id, String sail, String name, Double handicap, String variant)
    {
        StringBuilder sb = new StringBuilder("{\"boatId\":\"" + id + "\",\"sailno\":\"" + sail
            + "\",\"name\":\"" + name + "\"");
        if (handicap != null) sb.append(",\"handicap\":").append(handicap);
        if (variant != null) sb.append(",\"variant\":\"").append(variant).append("\"");
        return sb.append("}").toString();
    }

    @Test
    void theFleetIsImportedFromASailingPfExport() throws Exception
    {
        JsonNode report = importFleet(export(
            boatRow("5656-mondo-sydney38", "5656", "MONDO", 1.0809, "spin"),
            boatRow("MYC10-joss-jboatsj122", "MYC10", "Joss", 1.0635, "spin")));

        assertThat(report.path("ok").asBoolean(), is(true));
        assertThat(report.path("tally").path("CREATED").asInt(), equalTo(2));

        JsonNode boats = get("/api/boats");
        assertThat(boats.size(), equalTo(2));
        // The design comes out of the export's id, so the ids round-trip exactly.
        assertThat(report.path("rows").get(0).path("boatId").asText(),
            equalTo("5656-mondo-sydney38"));
        assertThat(report.path("rows").get(1).path("designId").asText(), equalTo("jboatsj122"));
    }

    @Test
    void theFleetImportIgnoresHandicapAndVariant() throws Exception
    {
        // Neither belongs to a boat, so neither may reach the register.
        importFleet(export(boatRow("5656-mondo-sydney38", "5656", "MONDO", 1.0809, "nonspin")));

        JsonNode boat = get("/api/boats").get(0);
        assertThat(boat.has("currentTcf"), is(false));
        assertThat(boat.has("spinnaker"), is(false));
    }

    @Test
    void reimportingTheSameExportChangesNothing() throws Exception
    {
        String json = export(boatRow("5656-mondo-sydney38", "5656", "MONDO", 1.0809, "spin"));
        importFleet(json);
        JsonNode second = importFleet(json);

        assertThat(second.path("tally").path("MATCHED").asInt(), equalTo(1));
        assertThat(second.path("tally").has("CREATED"), is(false));
        assertThat(get("/api/boats").size(), equalTo(1));
        // The id is the strongest evidence there is, and it matched on that alone.
        assertThat(second.path("rows").get(0).path("note").asText(), equalTo("matched on id"));
    }

    @Test
    void anExportUpgradesABoatWeHoldWithoutADesign() throws Exception
    {
        post("/api/boats", """
            {"sailNumber":"MYC99","name":"Newcomer"}""");
        assertThat(get("/api/boats").get(0).path("id").asText(), equalTo("MYC99-newcomer"));

        JsonNode report = importFleet(export(
            boatRow("MYC99-newcomer-farr40", "MYC99", "Newcomer", null, null)));

        assertThat(report.path("tally").path("UPGRADED").asInt(), equalTo(1));
        assertThat(get("/api/boats").size(), equalTo(1));
        assertThat(get("/api/boats").get(0).path("id").asText(), equalTo("MYC99-newcomer-farr40"));
    }

    @Test
    void anIdThatDisagreesWithItsRowYieldsNoDesign() throws Exception
    {
        // Better to register the boat without a design than to invent one from an id we
        // cannot read against the sail number and name beside it.
        JsonNode report = importFleet(export(
            boatRow("something-else-entirely", "MYC99", "Newcomer", null, null)));

        assertThat(get("/api/boats").get(0).path("designId").isNull(), is(true));
        assertThat(report.path("problems").get(0).asText(), containsString("does not match"));
    }

    @Test
    void aDryRunFleetImportChangesNothing() throws Exception
    {
        JsonNode report = post("/api/boats/import?dryRun=true",
            export(boatRow("5656-mondo-sydney38", "5656", "MONDO", 1.0809, "spin")));

        assertThat(report.path("rows").get(0).path("outcome").asText(), equalTo("PREVIEW"));
        assertThat(get("/api/boats").size(), equalTo(0));
    }

    @Test
    void junkIsReportedRatherThanCrashing() throws Exception
    {
        assertThat(post("/api/boats/import", "not json at all").path("problems").get(0).asText(),
            containsString("not JSON"));
        assertThat(post("/api/boats/import", "{\"hello\":1}").path("problems").get(0).asText(),
            containsString("expected a JSON array"));
        assertThat(get("/api/boats").size(), equalTo(0));
    }

    // --- entrants import ------------------------------------------------------

    @Test
    void entrantsAreImportedWithTheirHandicapAndVariant() throws Exception
    {
        String seriesId = createSeries("Twilight");
        String raceId = createRace(seriesId, "2026-06-05");

        JsonNode report = importEntrants(raceId, export(
            boatRow("5656-mondo-sydney38", "5656", "MONDO", 1.0809, "spin"),
            boatRow("MYC9-knotty-catalina28", "MYC9", "Knotty", 0.7331, "nonspin")));

        assertThat(report.path("entrants").asInt(), equalTo(2));
        JsonNode entrants = get("/api/races/" + raceId).path("entrants").path("entrants");
        assertThat(entrants.get(0).path("tcf").asDouble(), closeTo(1.0809, 1e-9));
        assertThat(entrants.get(0).path("spinnaker").asText(), equalTo("S"));
        assertThat(entrants.get(1).path("tcf").asDouble(), closeTo(0.7331, 1e-9));
        assertThat(entrants.get(1).path("spinnaker").asText(), equalTo("NS"));
        // Boats not yet known are registered as a side effect.
        assertThat(get("/api/boats").size(), equalTo(2));
    }

    @Test
    void reimportingEntrantsUpdatesTheHandicapWithoutDuplicating() throws Exception
    {
        String raceId = createRace(createSeries("Twilight"), "2026-06-05");
        importEntrants(raceId, export(boatRow("5656-mondo-sydney38", "5656", "MONDO", 1.0809, "spin")));
        JsonNode second = importEntrants(raceId,
            export(boatRow("5656-mondo-sydney38", "5656", "MONDO", 1.1000, "spin")));

        assertThat(second.path("rows").get(0).path("entered").asText(), equalTo("updated"));
        JsonNode entrants = get("/api/races/" + raceId).path("entrants").path("entrants");
        assertThat(entrants.size(), equalTo(1));
        assertThat(entrants.get(0).path("tcf").asDouble(), closeTo(1.1000, 1e-9));
    }

    @Test
    void anEntrantsImportLeavesBoatsMissingFromTheFileAlone() throws Exception
    {
        // A handicap export is a statement about ratings, not about who is racing tonight.
        String raceId = createRace(createSeries("Twilight"), "2026-06-05");
        importEntrants(raceId, export(
            boatRow("5656-mondo-sydney38", "5656", "MONDO", 1.0809, "spin"),
            boatRow("MYC10-joss-jboatsj122", "MYC10", "Joss", 1.0635, "spin")));

        importEntrants(raceId, export(boatRow("5656-mondo-sydney38", "5656", "MONDO", 1.09, "spin")));

        assertThat(get("/api/races/" + raceId).path("entrants").path("entrants").size(),
            equalTo(2));
    }

    @Test
    void aLockedRaceRefusesAnEntrantsImport() throws Exception
    {
        String seriesId = createSeries("Twilight");
        String boatId = createBoat("AUS9", "Quick Silver");
        putRoster(seriesId, boatId, 1.0);
        String raceId = createRace(seriesId, "2026-06-05");
        post("/api/races/" + raceId + "/entrants/seed", "{}");
        post("/api/races/" + raceId + "/save-handicaps", """
            {"adjustments":[{"boatId":"%s","finishPosition":1,"penaltyMinutes":6.0,
              "rewardMinutes":6.0,"netAdjustmentMinutes":0.0,"oldTcf":1.0,"newTcf":1.0}]}"""
            .formatted(boatId));

        HttpResponse<String> r = postRaw("/api/races/" + raceId + "/entrants/import",
            export(boatRow("5656-mondo-sydney38", "5656", "MONDO", 1.0809, "spin")));
        assertThat(r.statusCode(), equalTo(409));
    }

    // --- helpers -------------------------------------------------------------

    private String createSeries(String name) throws Exception
    {
        return post("/api/series", "{\"name\":\"" + name + "\"}")
            .path("series").path("id").asText();
    }

    private String createBoat(String sail, String name) throws Exception
    {
        return post("/api/boats", """
            {"sailNumber":"%s","name":"%s"}""".formatted(sail, name))
            .path("boat").path("id").asText();
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

    /** Series ids carry a slash; encode each segment and keep the separator. */
    private static String seriesPath(String id)
    {
        return java.util.Arrays.stream(id.split("/"))
            .map(x -> java.net.URLEncoder.encode(x, java.nio.charset.StandardCharsets.UTF_8))
            .reduce((a, b) -> a + "/" + b).orElse("");
    }

    private JsonNode importFleet(String json) throws Exception
    {
        return post("/api/boats/import", json);
    }

    private JsonNode importEntrants(String raceId, String json) throws Exception
    {
        return post("/api/races/" + raceId + "/entrants/import", json);
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
