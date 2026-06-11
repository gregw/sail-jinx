package org.mortbay.sailing.jinx.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.closeTo;
import static org.hamcrest.Matchers.is;

/**
 * Unit tests for the pure divisionTiming patch logic shared by the
 * {@code /division-starts} (Save) and {@code /process} (Process start times)
 * handlers. The handlers themselves talk to SailSys and aren't unit-tested;
 * the patch helper is the extractable pure logic.
 */
class ApiServletTest
{
    private static final ObjectMapper M = new ObjectMapper();

    /** Two-division template mirroring the SailSys /status divisionTiming shape. */
    private ArrayNode sampleTiming()
    {
        ArrayNode arr = M.createArrayNode();
        ObjectNode a = arr.addObject();
        a.put("divisionId", 13779);
        a.put("divisionName", "Div 1");
        a.put("startTimeLocal", "2026-05-17T18:13:00.000");
        a.put("startTimeUtc", "2026-05-17T08:13:00.000"); // local − 10h
        a.put("courseLength", 8.0);
        a.put("raceType", 0);
        a.put("isAbandoned", false);
        a.put("isShortened", false);
        ObjectNode b = arr.addObject();
        b.put("divisionId", 13780);
        b.put("divisionName", "Div 2");
        b.put("startTimeLocal", "2026-05-17T18:18:00.000");
        b.put("startTimeUtc", "2026-05-17T08:18:00.000");
        b.put("courseLength", 6.0);
        b.put("raceType", 0);
        b.put("isAbandoned", false);
        b.put("isShortened", false);
        return arr;
    }

    private JsonNode division(ArrayNode timing, int divisionId)
    {
        for (JsonNode dt : timing)
            if (dt.path("divisionId").asInt() == divisionId) return dt;
        throw new AssertionError("division " + divisionId + " not found");
    }

    @Test
    void setsStartTimeFromHhmmAndPreservesUtcOffset()
    {
        ObjectNode divisions = M.createObjectNode();
        divisions.putObject("13779").put("startTimeLocal", "18:00");

        ArrayNode patched = ApiServlet.patchDivisionTiming(sampleTiming(), divisions);
        JsonNode d = division(patched, 13779);

        assertThat(d.path("startTimeLocal").asText(), is("2026-05-17T18:00:00.000"));
        // UTC recomputed preserving the original 10h offset.
        assertThat(d.path("startTimeUtc").asText(), is("2026-05-17T08:00:00.000"));
        // Untouched fields preserved.
        assertThat(d.path("courseLength").asDouble(), closeTo(8.0, 1e-9));
        assertThat(d.path("divisionName").asText(), is("Div 1"));
    }

    @Test
    void setsPerDivisionCourseLengthForPursuit()
    {
        ObjectNode divisions = M.createObjectNode();
        ObjectNode spec = divisions.putObject("13779");
        spec.put("startTimeLocal", "18:00");
        spec.put("courseLength", 12.0);

        ArrayNode patched = ApiServlet.patchDivisionTiming(sampleTiming(), divisions);
        JsonNode d = division(patched, 13779);

        assertThat(d.path("startTimeLocal").asText(), is("2026-05-17T18:00:00.000"));
        assertThat(d.path("courseLength").asDouble(), closeTo(12.0, 1e-9));
    }

    @Test
    void leavesUnreferencedDivisionsUntouched()
    {
        ObjectNode divisions = M.createObjectNode();
        divisions.putObject("13779").put("startTimeLocal", "18:00");

        ArrayNode patched = ApiServlet.patchDivisionTiming(sampleTiming(), divisions);
        JsonNode d = division(patched, 13780);

        assertThat(d.path("startTimeLocal").asText(), is("2026-05-17T18:18:00.000"));
        assertThat(d.path("startTimeUtc").asText(), is("2026-05-17T08:18:00.000"));
        assertThat(d.path("courseLength").asDouble(), closeTo(6.0, 1e-9));
    }

    @Test
    void courseLengthOnlyLeavesStartTimeUntouched()
    {
        ObjectNode divisions = M.createObjectNode();
        divisions.putObject("13780").put("courseLength", 9.5);

        ArrayNode patched = ApiServlet.patchDivisionTiming(sampleTiming(), divisions);
        JsonNode d = division(patched, 13780);

        assertThat(d.path("startTimeLocal").asText(), is("2026-05-17T18:18:00.000"));
        assertThat(d.path("courseLength").asDouble(), closeTo(9.5, 1e-9));
    }

    @Test
    void doesNotMutateTheInputTiming()
    {
        ArrayNode original = sampleTiming();
        ObjectNode divisions = M.createObjectNode();
        divisions.putObject("13779").put("startTimeLocal", "18:00");

        ApiServlet.patchDivisionTiming(original, divisions);

        assertThat(division(original, 13779).path("startTimeLocal").asText(),
            is("2026-05-17T18:13:00.000"));
    }

    // ---- buildAbandonStarters: the post-processing ("results already
    // processed") abandon path, where SailSys rejects /divisions/abandon and
    // instead wants every boat in the abandoned division flagged ABD via
    // PUT /results/starters. The HTTP round-trip isn't unit-tested; this pure
    // payload builder is the extractable logic. ----

    /** Starters template: two boats in div 16097, one in div 16098. */
    private ArrayNode sampleStarters()
    {
        ArrayNode arr = M.createArrayNode();
        ObjectNode a = arr.addObject();
        a.put("boatId", 1330); a.put("divisionId", 16097);
        a.put("startedRace", false); a.putNull("finishTime");
        a.set("penalties", M.createArrayNode());
        ObjectNode b = arr.addObject();
        b.put("boatId", 767); b.put("divisionId", 16097);
        b.put("startedRace", true); b.put("finishTime", "2026-06-02T19:00:00.000");
        b.set("penalties", M.createArrayNode());
        ObjectNode c = arr.addObject();
        c.put("boatId", 999); c.put("divisionId", 16098);
        c.put("startedRace", true); c.put("finishTime", "2026-06-02T19:05:00.000");
        c.set("penalties", M.createArrayNode());
        return arr;
    }

    private ObjectNode abdPenalty(int id)
    {
        ObjectNode p = M.createObjectNode();
        p.put("id", id);
        p.put("definitionId", 25);
        p.put("definitionShortName", "ABD");
        return p;
    }

    private JsonNode starter(ArrayNode starters, int boatId)
    {
        for (JsonNode b : starters)
            if (b.path("boatId").asInt() == boatId) return b;
        throw new AssertionError("boat " + boatId + " not found");
    }

    @Test
    void abandonFlagsTargetDivisionBoatsAbdAndLeavesOthers()
    {
        Map<Integer, JsonNode> abd = Map.of(16097, abdPenalty(19802));
        ArrayNode out = ApiServlet.buildAbandonStarters(
            sampleStarters(), Set.of(16097), abd, "2026-06-02");

        JsonNode a = starter(out, 1330);
        assertThat(a.path("startedRace").asBoolean(), is(true));
        assertThat(a.path("finishTime").isNull(), is(true));
        assertThat(a.path("finishDate").asText(), is("2026-06-02T00:00:00.000"));
        assertThat(a.path("penalties").size(), is(1));
        assertThat(a.path("penalties").get(0).path("definitionShortName").asText(), is("ABD"));

        // Boat in a non-abandoned division is left exactly as the template had it.
        JsonNode c = starter(out, 999);
        assertThat(c.path("startedRace").asBoolean(), is(true));
        assertThat(c.path("finishTime").asText(), is("2026-06-02T19:05:00.000"));
        assertThat(c.path("penalties").size(), is(0));
    }

    @Test
    void abandonClearsFinishTimeOfStartedBoatInAbandonedDivision()
    {
        ArrayNode out = ApiServlet.buildAbandonStarters(
            sampleStarters(), Set.of(16097), Map.of(16097, abdPenalty(19802)), "2026-06-02");
        JsonNode b = starter(out, 767);
        assertThat(b.path("startedRace").asBoolean(), is(true));
        assertThat(b.path("finishTime").isNull(), is(true));
        assertThat(b.path("penalties").get(0).path("definitionShortName").asText(), is("ABD"));
    }

    @Test
    void abandonWithNoAbdPenaltyForDivisionStillFlagsStartedWithEmptyPenalties()
    {
        // Defensive: if the division's penalty catalogue lacks ABD, the boats
        // are still marked started with finishTime cleared (no penalty added).
        ArrayNode out = ApiServlet.buildAbandonStarters(
            sampleStarters(), Set.of(16097), Map.of(), "2026-06-02");
        JsonNode a = starter(out, 1330);
        assertThat(a.path("startedRace").asBoolean(), is(true));
        assertThat(a.path("penalties").size(), is(0));
    }

    @Test
    void abandonDoesNotMutateTheInputStarters()
    {
        ArrayNode original = sampleStarters();
        ApiServlet.buildAbandonStarters(original, Set.of(16097),
            Map.of(16097, abdPenalty(19802)), "2026-06-02");
        assertThat(starter(original, 1330).path("startedRace").asBoolean(), is(false));
        assertThat(starter(original, 767).path("finishTime").asText(), is("2026-06-02T19:00:00.000"));
    }
}
