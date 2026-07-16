package org.mortbay.sailing.jinx.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.closeTo;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

/**
 * Unit tests for the pure projection helpers behind
 * {@code GET /api/series/{id}/entries}. The handler itself talks to SailSys
 * and isn't unit-tested; the JSON-shaping logic is the extractable pure part.
 * Sample payloads mirror the observed SailSys shapes
 * ({@code PUT /series/{id}/entries} list and {@code GET /series/{id}/entries/{boatId}}
 * detail) captured in the HAR reference — see wiki/sailsys-api-reference.md.
 */
class SeriesEntriesProjectionTest
{
    private static final ObjectMapper M = new ObjectMapper();
    private static final int MYC_TCF = 15;

    /** One series entry as returned by PUT /series/{id}/entries. */
    private ObjectNode sampleEntry(int boatId, String name)
    {
        ObjectNode e = M.createObjectNode();
        ObjectNode skipper = e.putObject("skipper");
        skipper.put("userId", 1563);
        skipper.put("fullName", "David Ashton");
        skipper.put("email", "dashton@example.com");
        skipper.put("phoneNumber", "417691197");
        e.put("paymentMethod", 1);
        e.put("entryReceiptStatus", 0);
        e.put("seriesEntryStatus", 1);
        e.put("entryType", 2);
        e.put("boatName", name);
        e.put("boatSailNumber", "R350");
        e.put("boatMake", "Archambault ");
        e.put("boatModel", "A35");
        e.put("divisionId", 16363);
        e.put("divisionName", "Open Division");
        e.put("divisionApproved", true);
        e.put("spinnakerType", 2);
        e.put("handicapApproved", true);
        e.put("boatId", boatId);
        e.put("entryProgress", 100);
        e.putArray("entryStagesNeeded");
        ArrayNode handicaps = e.putArray("handicaps");
        ObjectNode h = handicaps.addObject();
        h.putObject("definition").put("id", MYC_TCF).put("shortName", "TCF");
        h.put("id", 499320);
        h.put("boatId", boatId);
        h.put("value", 1.0161);
        h.put("spinnakerType", 2);
        return e;
    }

    @Test
    void projectsTheTableFields()
    {
        ArrayNode entries = M.createArrayNode();
        entries.add(sampleEntry(8107, "Absolut"));

        List<Map<String, Object>> out = ApiServlet.projectSeriesEntries(entries, MYC_TCF);

        assertThat(out, hasSize(1));
        Map<String, Object> row = out.get(0);
        assertThat(row.get("boatId"), is(8107));
        assertThat(row.get("sailNumber"), is("R350"));
        assertThat(row.get("boatName"), is("Absolut"));
        assertThat(row.get("make"), is("Archambault "));
        assertThat(row.get("model"), is("A35"));
        assertThat(row.get("divisionId"), is(16363));
        assertThat(row.get("divisionName"), is("Open Division"));
        assertThat(row.get("spinnakerType"), is(2));
        assertThat(row.get("entryType"), is(2));
        assertThat(row.get("seriesEntryStatus"), is(1));
        assertThat(row.get("entryProgress"), is(100));
        assertThat(row.get("entryStagesNeeded"), is(List.of()));
        assertThat((Double)row.get("tcf"), closeTo(1.0161, 1e-9));
        assertThat(row.get("tcfSpinnakerType"), is(2));
    }

    @Test
    void neverLeaksSkipperContactDetails()
    {
        ArrayNode entries = M.createArrayNode();
        entries.add(sampleEntry(8107, "Absolut"));

        Map<String, Object> row = ApiServlet.projectSeriesEntries(entries, MYC_TCF).get(0);

        assertThat(row.keySet(), containsInAnyOrder(
            "boatId", "sailNumber", "boatName", "make", "model",
            "divisionId", "divisionName", "spinnakerType", "entryType",
            "seriesEntryStatus", "entryProgress", "entryStagesNeeded",
            "tcf", "tcfSpinnakerType"));
    }

    @Test
    void tcfPickedByConfiguredDefinitionId()
    {
        ObjectNode e = sampleEntry(8107, "Absolut");
        // Prepend a PHS (definition 5) handicap row — the MYC TCF row must win.
        ArrayNode handicaps = M.createArrayNode();
        ObjectNode phs = handicaps.addObject();
        phs.putObject("definition").put("id", 5).put("shortName", "PHS");
        phs.put("value", 0.9);
        phs.put("spinnakerType", 1);
        handicaps.addAll((ArrayNode)e.get("handicaps"));
        e.set("handicaps", handicaps);
        ArrayNode entries = M.createArrayNode();
        entries.add(e);

        Map<String, Object> row = ApiServlet.projectSeriesEntries(entries, MYC_TCF).get(0);

        assertThat((Double)row.get("tcf"), closeTo(1.0161, 1e-9));
        assertThat(row.get("tcfSpinnakerType"), is(2));
    }

    @Test
    void missingHandicapGivesNullTcfAndEntrySpinnakerFallback()
    {
        ObjectNode e = sampleEntry(14278, "Audrey");
        e.putArray("handicaps");
        e.put("seriesEntryStatus", 0);
        e.put("entryProgress", 50);
        e.putArray("entryStagesNeeded").add(2).add(6);
        ArrayNode entries = M.createArrayNode();
        entries.add(e);

        Map<String, Object> row = ApiServlet.projectSeriesEntries(entries, MYC_TCF).get(0);

        assertThat(row.get("tcf"), nullValue());
        // No handicap row to match, so the entry's own spinnakerType is what a
        // subsequent TCF write must carry.
        assertThat(row.get("tcfSpinnakerType"), is(2));
        assertThat(row.get("seriesEntryStatus"), is(0));
        assertThat(row.get("entryProgress"), is(50));
        assertThat(row.get("entryStagesNeeded"), is(List.of(2, 6)));
    }

    @Test
    void projectsDivisionsFromEntryDetail()
    {
        // GET /series/{id}/entries/{boatId} shape: divisions[] alongside much more.
        ObjectNode detail = M.createObjectNode();
        ArrayNode divisions = detail.putArray("divisions");
        for (int i = 0; i < 3; i++)
        {
            ObjectNode d = divisions.addObject();
            d.put("id", 10421 + i);
            d.put("name", i == 0 ? "Yachts" : ("Div " + i));
            d.put("divisionType", 0);
            d.put("spinnakerType", 3);
            d.put("defaultHandicap", 5);
        }

        List<Map<String, Object>> out = ApiServlet.projectDivisions(detail);

        assertThat(out, hasSize(3));
        assertThat(out.get(0), is(Map.of("id", 10421, "name", "Yachts", "spinnakerType", 3)));
        assertThat(out.stream().map(d -> d.get("name")).toList(),
            contains("Yachts", "Div 1", "Div 2"));
    }

    @Test
    void projectDivisionsToleratesMissingArray()
    {
        JsonNode detail = M.createObjectNode();
        assertThat(ApiServlet.projectDivisions(detail), hasSize(0));
    }
}
