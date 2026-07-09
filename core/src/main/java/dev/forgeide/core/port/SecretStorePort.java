package dev.forgeide.core.port;

import java.util.List;
import java.util.Map;

/**
 * Env-scoping (SDD SR-5/Т-11): the only path secrets (git tokens, MCP credentials) reach an
 * agent phase's environment. {@code PipelineEngine} calls {@link #resolve} with a step's own
 * {@code env_scope} and injects exactly (and only) what comes back — a step with an empty
 * {@code env_scope} gets an empty map, never the full secret set, and a step whose {@code
 * env_scope} names a key with no stored value simply doesn't see that key (not a blank value),
 * so e.g. a {@code git push} with no configured token fails on its own.
 *
 * <p>Implemented against the IDE's own config store in {@code runtime} ({@code FileSecretStore},
 * {@code ~/.forgeide/secrets.json}, mode 600); {@code core} only knows the contract, same split
 * as {@link StateStore}.
 */
public interface SecretStorePort {

    /** No-op implementation for engines/tests that don't exercise env-scoping — always empty,
     * regardless of {@code envScope}. */
    SecretStorePort NOOP = envScope -> Map.of();

    /** @return only the {@code envScope} keys that have a stored secret. */
    Map<String, String> resolve(List<String> envScope);
}
