package dev.forgeide.core.run;

import java.util.Arrays;
import java.util.Optional;

/**
 * The two actions offered when a run is {@code STOPPED(harness-drift)} (SDD SR-8, FR-8.3, T18):
 * a human either trusts the drifted content as the new baseline, or restores the project's
 * harness (`hooks/` — including the `settings.hooks.json` template — and `skills/`) from the last
 * known-good IDE cache copy.
 */
public enum HarnessDriftAction {
    ACCEPT("accept"),
    ROLLBACK("rollback");

    private final String token;

    HarnessDriftAction(String token) {
        this.token = token;
    }

    public String token() {
        return token;
    }

    public static Optional<HarnessDriftAction> fromToken(String token) {
        return Arrays.stream(values()).filter(a -> a.token.equals(token)).findFirst();
    }
}
