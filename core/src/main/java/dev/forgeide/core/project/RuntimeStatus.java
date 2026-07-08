package dev.forgeide.core.project;

/** Result of probing a {@link RuntimeBinding} binary (SDD FR-1.2). */
public enum RuntimeStatus {
    /** Never checked in this session. */
    UNKNOWN,
    AVAILABLE,
    UNAVAILABLE
}
