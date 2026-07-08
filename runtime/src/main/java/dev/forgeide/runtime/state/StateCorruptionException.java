package dev.forgeide.runtime.state;

/**
 * Thrown when {@code run.json}'s embedded checksum doesn't match its content (SDD FR-7.1).
 * Unlike a broken audit hash-chain (which the engine can name a run-level status for,
 * {@code STOPPED(audit-chain)}), a corrupt run.json means the source of truth itself
 * cannot be trusted, so there is no snapshot to hand back — the caller must surface this
 * as a hard failure rather than a run status.
 */
public final class StateCorruptionException extends RuntimeException {

    public StateCorruptionException(String message) {
        super(message);
    }
}
