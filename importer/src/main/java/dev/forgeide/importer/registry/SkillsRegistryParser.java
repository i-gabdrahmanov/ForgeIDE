package dev.forgeide.importer.registry;

import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Reads every markdown pipe-table out of {@code SKILLS-REGISTRY.md} whose header names an
 * id column ({@code Skill} or {@code Hook} — the real registry has one table for each, SD §8)
 * plus {@code Owner}/{@code Validity} columns. {@code Scope}/{@code Evals} are read when present,
 * else left blank rather than failing the whole file over one malformed table.
 */
public final class SkillsRegistryParser {

    private static final Pattern SEPARATOR_ROW = Pattern.compile("^\\|?[\\s:-]+\\|[\\s:|-]*$");

    private SkillsRegistryParser() {
    }

    public static List<SkillsRegistryEntry> parse(String markdown) {
        List<SkillsRegistryEntry> entries = new ArrayList<>();
        String[] lines = markdown.split("\n", -1);
        int i = 0;
        while (i < lines.length) {
            String line = lines[i].strip();
            if (isRow(line) && i + 1 < lines.length && SEPARATOR_ROW.matcher(lines[i + 1].strip()).matches()) {
                Map<String, Integer> columns = header(cells(line));
                i += 2;
                while (i < lines.length && isRow(lines[i].strip())) {
                    parseRow(cells(lines[i].strip()), columns).ifPresent(entries::add);
                    i++;
                }
            } else {
                i++;
            }
        }
        return entries;
    }

    private static boolean isRow(String line) {
        return line.startsWith("|");
    }

    private static List<String> cells(String row) {
        String trimmed = row.strip();
        if (trimmed.startsWith("|")) {
            trimmed = trimmed.substring(1);
        }
        if (trimmed.endsWith("|")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        List<String> cells = new ArrayList<>();
        for (String cell : trimmed.split("\\|", -1)) {
            cells.add(cell.strip());
        }
        return cells;
    }

    /** @return column name (lower-cased) to index, only when both an id column and Owner/Validity exist. */
    private static Map<String, Integer> header(List<String> headerCells) {
        Map<String, Integer> columns = new LinkedHashMap<>();
        for (int i = 0; i < headerCells.size(); i++) {
            columns.put(headerCells.get(i).toLowerCase(Locale.ROOT), i);
        }
        return columns;
    }

    private static Optional<SkillsRegistryEntry> parseRow(List<String> cells, Map<String, Integer> columns) {
        Integer idColumn = columns.containsKey("skill") ? columns.get("skill") : columns.get("hook");
        Integer ownerColumn = columns.get("owner");
        Integer validityColumn = columns.get("validity");
        if (idColumn == null || ownerColumn == null || validityColumn == null) {
            return Optional.empty(); // not a registry table (e.g. an unrelated pipe-table in the doc)
        }
        String id = cell(cells, idColumn);
        if (id.isBlank()) {
            return Optional.empty();
        }
        String owner = cell(cells, ownerColumn);
        Optional<YearMonth> validUntil = parseValidity(cell(cells, validityColumn));
        String scope = columns.containsKey("scope") ? cell(cells, columns.get("scope")) : "";
        String evals = columns.containsKey("evals") ? cell(cells, columns.get("evals")) : "";
        return Optional.of(new SkillsRegistryEntry(id, owner, validUntil, scope, evals));
    }

    private static String cell(List<String> cells, int index) {
        return index < cells.size() ? cells.get(index) : "";
    }

    private static Optional<YearMonth> parseValidity(String text) {
        try {
            return Optional.of(YearMonth.parse(text));
        } catch (DateTimeParseException e) {
            return Optional.empty(); // "—", free text, or genuinely absent — not an error
        }
    }
}
