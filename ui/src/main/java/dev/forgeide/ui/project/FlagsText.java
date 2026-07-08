package dev.forgeide.ui.project;

import java.util.ArrayList;
import java.util.List;

/** Runtime flags (e.g. {@code --experimental-hooks}) are edited as one space-separated field. */
final class FlagsText {

    private FlagsText() {
    }

    static List<String> parse(String text) {
        List<String> flags = new ArrayList<>();
        if (text == null) {
            return flags;
        }
        for (String token : text.trim().split("\\s+")) {
            if (!token.isBlank()) {
                flags.add(token);
            }
        }
        return flags;
    }

    static String format(List<String> flags) {
        return String.join(" ", flags);
    }
}
