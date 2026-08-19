package org.mortbay.sailing.jinx.identity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.mortbay.sailing.jinx.model.Spinnaker;

/**
 * Parses a fleet list pasted or uploaded as CSV.
 *
 * <p>Club fleet lists come out of spreadsheets, other systems, and email, and no two have
 * the same columns in the same order. So the header row decides: each heading is
 * normalised and matched against the spellings below, and anything unrecognised is
 * ignored rather than rejected.
 *
 * <table>
 *   <caption>Recognised headings</caption>
 *   <tr><th>Field</th><th>Accepted headings</th></tr>
 *   <tr><td>sail number</td><td>sail, sailno, sailnumber, sail number, sail #, no, number</td></tr>
 *   <tr><td>name</td><td>name, boat, boatname, boat name, yacht</td></tr>
 *   <tr><td>design</td><td>design, class, type, model, make</td></tr>
 *   <tr><td>TCF</td><td>tcf, handicap, hcap</td></tr>
 *   <tr><td>division</td><td>division, div, fleet</td></tr>
 *   <tr><td>spinnaker</td><td>spinnaker, spin, s/ns, kite</td></tr>
 *   <tr><td>notes</td><td>notes, note, comment, comments</td></tr>
 * </table>
 *
 * <p><b>Every column is optional</b>, including the design — a fleet list frequently has
 * only sail numbers and names. A boat imported without a design is registered without
 * one and upgraded in place when a later import supplies it, rather than becoming a
 * second boat. See {@link BoatRegistry}.
 */
public final class BoatCsv
{
    /** normalised heading → field, first match wins. */
    private static final Map<String, Field> HEADINGS = new LinkedHashMap<>();

    private enum Field
    {
        SAIL, NAME, DESIGN, TCF, DIVISION, SPINNAKER, NOTES
    }

    static
    {
        for (String h : List.of("sail", "sailno", "sailnumber", "sailnum", "no", "number", "bow"))
            HEADINGS.put(h, Field.SAIL);
        for (String h : List.of("name", "boat", "boatname", "yacht", "vessel"))
            HEADINGS.put(h, Field.NAME);
        for (String h : List.of("design", "class", "type", "model", "make", "boattype"))
            HEADINGS.put(h, Field.DESIGN);
        for (String h : List.of("tcf", "handicap", "hcap", "rating"))
            HEADINGS.put(h, Field.TCF);
        for (String h : List.of("division", "div", "fleet"))
            HEADINGS.put(h, Field.DIVISION);
        for (String h : List.of("spinnaker", "spin", "sns", "kite"))
            HEADINGS.put(h, Field.SPINNAKER);
        for (String h : List.of("notes", "note", "comment", "comments"))
            HEADINGS.put(h, Field.NOTES);
    }

    private BoatCsv()
    {
    }

    /**
     * One parsed row: the boat's identity, and separately the terms it would enter a
     * series on. They are kept apart because they belong to different things — the hull
     * goes in the register, the terms go on a roster — and a fleet list happens to carry
     * both in one line.
     */
    public record Row(int line, BoatRegistry.RawBoat boat, EntryTerms terms)
    {
    }

    /**
     * The per-entry columns of a fleet list. All nullable: a list may carry none of them,
     * and they only mean anything once a series is named to apply them to.
     */
    public record EntryTerms(Double tcf, String division, Spinnaker spinnaker)
    {
        public boolean isEmpty()
        {
            return tcf == null && division == null && spinnaker == null;
        }
    }

    /** What came out of a parse: the rows, and what the header row was understood to mean. */
    public record Parsed(List<Row> rows, List<String> recognisedColumns, List<String> ignoredColumns,
                         List<String> problems)
    {
    }

    /**
     * Parse CSV text. Never throws: a row that cannot be read becomes a problem message
     * and the rest of the file is still imported, because a single bad line in a
     * forty-boat list should not cost the other thirty-nine.
     */
    public static Parsed parse(String csv)
    {
        List<Row> rows = new ArrayList<>();
        List<String> recognised = new ArrayList<>();
        List<String> ignored = new ArrayList<>();
        List<String> problems = new ArrayList<>();

        if (csv == null || csv.isBlank())
        {
            problems.add("the file is empty");
            return new Parsed(rows, recognised, ignored, problems);
        }

        List<List<String>> lines = new ArrayList<>();
        for (String line : csv.split("\r?\n"))
        {
            if (!line.isBlank())
                lines.add(splitCsvLine(line));
        }
        if (lines.isEmpty())
        {
            problems.add("the file is empty");
            return new Parsed(rows, recognised, ignored, problems);
        }

        List<String> header = lines.getFirst();
        Map<Integer, Field> columns = new LinkedHashMap<>();
        for (int i = 0; i < header.size(); i++)
        {
            String raw = header.get(i);
            Field field = HEADINGS.get(normaliseHeading(raw));
            if (field == null)
            {
                if (!raw.isBlank())
                    ignored.add(raw.trim());
            }
            else if (columns.containsValue(field))
            {
                // Two columns claiming the same field: the first wins, so a spreadsheet
                // with both "Class" and "Type" does not silently take the emptier one.
                ignored.add(raw.trim() + " (duplicate " + field.name().toLowerCase(Locale.ENGLISH) + ")");
            }
            else
            {
                columns.put(i, field);
                recognised.add(raw.trim() + " → " + field.name().toLowerCase(Locale.ENGLISH));
            }
        }

        if (!columns.containsValue(Field.SAIL) && !columns.containsValue(Field.NAME))
        {
            problems.add("no sail-number or name column found — the first row must be a header. "
                + "Recognised headings include: sail, name, design, tcf, division, spinnaker");
            return new Parsed(rows, recognised, ignored, problems);
        }

        for (int i = 1; i < lines.size(); i++)
        {
            List<String> cells = lines.get(i);
            int lineNo = i + 1;
            String sail = null;
            String name = null;
            String design = null;
            String division = null;
            String notes = null;
            Spinnaker spinnaker = null;
            Double tcf = null;

            for (Map.Entry<Integer, Field> e : columns.entrySet())
            {
                String value = e.getKey() < cells.size() ? cells.get(e.getKey()).trim() : "";
                if (value.isEmpty())
                    continue;
                switch (e.getValue())
                {
                    case SAIL -> sail = value;
                    case NAME -> name = value;
                    case DESIGN -> design = value;
                    case DIVISION -> division = value;
                    case NOTES -> notes = value;
                    case SPINNAKER -> spinnaker = spinnakerOf(value);
                    case TCF ->
                    {
                        try
                        {
                            tcf = Double.parseDouble(value.replace(",", "."));
                        }
                        catch (NumberFormatException ex)
                        {
                            // Keep the boat, drop the number: a typo'd handicap is worth
                            // flagging, but not worth losing the boat over.
                            problems.add("line " + lineNo + ": '" + value + "' is not a number, "
                                + "TCF left unset");
                        }
                    }
                }
            }

            if ((sail == null || sail.isBlank()) && (name == null || name.isBlank()))
            {
                problems.add("line " + lineNo + ": no sail number and no name — skipped");
                continue;
            }
            rows.add(new Row(lineNo,
                new BoatRegistry.RawBoat(sail, name, design, notes, false),
                new EntryTerms(tcf, division, spinnaker)));
        }

        if (rows.isEmpty() && problems.isEmpty())
            problems.add("the file has a header but no boats");
        return new Parsed(rows, recognised, ignored, problems);
    }

    private static String normaliseHeading(String raw)
    {
        return raw == null ? "" : raw.toLowerCase(Locale.ENGLISH).replaceAll("[^a-z0-9]", "");
    }

    /** Anything starting with "n" — non-spinnaker, NS, no — means no kite. */
    private static Spinnaker spinnakerOf(String value)
    {
        String v = value.trim().toLowerCase(Locale.ENGLISH);
        if (v.startsWith("n"))
            return Spinnaker.NS;
        if (v.startsWith("y") || v.startsWith("s") || v.startsWith("k"))
            return Spinnaker.S;
        return null;
    }

    /**
     * Split one CSV line, honouring double quotes and doubled-quote escapes. Boat names
     * contain commas ("Kayimai, Too") often enough that a plain split is not safe.
     */
    static List<String> splitCsvLine(String line)
    {
        List<String> cells = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++)
        {
            char c = line.charAt(i);
            if (quoted)
            {
                if (c == '"')
                {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"')
                    {
                        cell.append('"');
                        i++;
                    }
                    else
                    {
                        quoted = false;
                    }
                }
                else
                {
                    cell.append(c);
                }
            }
            else if (c == '"')
            {
                quoted = true;
            }
            else if (c == ',' || c == ';' || c == '\t')
            {
                cells.add(cell.toString());
                cell.setLength(0);
            }
            else
            {
                cell.append(c);
            }
        }
        cells.add(cell.toString());
        return cells;
    }
}
