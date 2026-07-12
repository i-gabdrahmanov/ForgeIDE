package dev.forgeide.runtime.process;

/**
 * Extension point for OS-level isolation of agent/script phases (SDD §8 — closes T-2/T-18 to
 * class P instead of D; this interface is the scaffold, a real implementation is post-MVP).
 * {@link ProcessRunner#run} calls {@link #wrap} on every {@link ProcessSpec} immediately before
 * {@link ProcessGroupLauncher#start}, giving an implementation the chance to rewrite the
 * command/env/working directory so the child actually launches inside a sandbox
 * (sandbox-exec/bubblewrap/container). The default {@link NoSandbox} is the identity function.
 */
public interface PhaseSandbox {

    /** Returns the {@link ProcessSpec} to actually launch; may return {@code spec} unchanged. */
    ProcessSpec wrap(ProcessSpec spec);
}
