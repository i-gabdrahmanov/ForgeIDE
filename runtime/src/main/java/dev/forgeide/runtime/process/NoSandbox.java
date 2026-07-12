package dev.forgeide.runtime.process;

/** Default {@link PhaseSandbox} — no isolation, the process launches exactly as specified. */
public final class NoSandbox implements PhaseSandbox {

    public static final NoSandbox INSTANCE = new NoSandbox();

    private NoSandbox() {
    }

    @Override
    public ProcessSpec wrap(ProcessSpec spec) {
        return spec;
    }
}
