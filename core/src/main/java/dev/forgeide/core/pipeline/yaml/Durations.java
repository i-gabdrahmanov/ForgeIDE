package dev.forgeide.core.pipeline.yaml;

import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses and formats the compact duration literals used in {@code pipeline.yaml}
 * ({@code 40m}, {@code 2h}, {@code 500ms}). ISO-8601 ({@code PT40M}) is also accepted on
 * read so hand-written and machine-written files interoperate. Formatting picks the largest
 * whole unit, which keeps {@code parse(format(d)).equals(d)} for round-trips.
 *
 * <p>Public so the T22 canvas config forms can accept/display the same literals the YAML
 * itself uses, instead of reimplementing this parsing in the ui module.
 */
public final class Durations {

    private static final Pattern COMPACT = Pattern.compile("(\\d+)\\s*(ms|s|m|h|d)");

    private Durations() {
    }

    public static Duration parse(String raw) {
        String text = raw.strip();
        Matcher m = COMPACT.matcher(text);
        if (m.matches()) {
            long amount = Long.parseLong(m.group(1));
            return switch (m.group(2)) {
                case "ms" -> Duration.ofMillis(amount);
                case "s" -> Duration.ofSeconds(amount);
                case "m" -> Duration.ofMinutes(amount);
                case "h" -> Duration.ofHours(amount);
                case "d" -> Duration.ofDays(amount);
                default -> throw new IllegalArgumentException("unreachable unit");
            };
        }
        return Duration.parse(text); // ISO-8601 fallback
    }

    public static String format(Duration d) {
        long ms = d.toMillis();
        if (ms == 0) {
            return "0s";
        }
        if (ms % 86_400_000 == 0) {
            return (ms / 86_400_000) + "d";
        }
        if (ms % 3_600_000 == 0) {
            return (ms / 3_600_000) + "h";
        }
        if (ms % 60_000 == 0) {
            return (ms / 60_000) + "m";
        }
        if (ms % 1_000 == 0) {
            return (ms / 1_000) + "s";
        }
        return ms + "ms";
    }
}
