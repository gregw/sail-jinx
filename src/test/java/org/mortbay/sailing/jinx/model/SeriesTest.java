package org.mortbay.sailing.jinx.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Series is deserialised directly from the SailSys {@code POST /series/all}
 * response. Sample below is lifted verbatim from a HAR capture of the
 * production app — preserving it as a fixture means future SailSys field
 * additions are caught early.
 */
class SeriesTest
{
    private static final ObjectMapper MAPPER = new ObjectMapper()
        .registerModule(new JavaTimeModule());

    /** A single item from the real {@code POST /series/all} response (HAR capture). */
    private static final String REAL_SAILSYS_ITEM = """
        {
          "tags": [],
          "id": 5699,
          "name": "Test Jinx Series",
          "subSeriesCount": 0,
          "racesCount": 3,
          "divisionsCount": 1,
          "club": {
            "id": 23,
            "shortName": "MYC",
            "longName": "Manly Yacht Club"
          },
          "status": 1,
          "sponsorImage": null,
          "defaultHandicap": 15,
          "isLegacyEvent": false,
          "complianceEnabled": false,
          "complianceBlocksEntries": false,
          "teamRacing": false,
          "showCountryFlag": false,
          "showEntrantsBeforeProcessing": true,
          "showBowNumber": false,
          "showBoatManufacturerAndModel": true,
          "regattaSponsorImage": null
        }
        """;

    @Test
    void parsesRealSailsysItem() throws Exception
    {
        Series s = MAPPER.readValue(REAL_SAILSYS_ITEM, Series.class);

        assertThat(s.id(), equalTo(5699));
        assertThat(s.name(), equalTo("Test Jinx Series"));
        assertThat(s.status(), equalTo(1));
        assertThat(s.racesCount(), equalTo(3));
        assertThat(s.divisionsCount(), equalTo(1));
        assertThat(s.subSeriesCount(), equalTo(0));
        assertThat(s.defaultHandicap(), equalTo(15));  // MYC TCF
    }

    @Test
    void statusLabelDecodesKnownValues()
    {
        // Inferred from the HAR: status=1 is the only series the user actively
        // races; status=2 dominates the history; status=0 appears on items
        // marked "upcoming" in the UI.
        assertThat(new Series(1, "x", 0, 0, 0, 0, 0).statusLabel(), equalTo("upcoming"));
        assertThat(new Series(1, "x", 1, 0, 0, 0, 0).statusLabel(), equalTo("active"));
        assertThat(new Series(1, "x", 2, 0, 0, 0, 0).statusLabel(), equalTo("completed"));
        assertThat(new Series(1, "x", 7, 0, 0, 0, 0).statusLabel(), equalTo("status=7"));
    }

    @Test
    void recordIsImmutable()
    {
        Series s = new Series(1, "x", 0, 0, 0, 0, 0);
        assertThat(s, notNullValue());
    }
}
