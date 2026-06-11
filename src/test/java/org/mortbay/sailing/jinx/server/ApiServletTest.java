package org.mortbay.sailing.jinx.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.Test;

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
}
