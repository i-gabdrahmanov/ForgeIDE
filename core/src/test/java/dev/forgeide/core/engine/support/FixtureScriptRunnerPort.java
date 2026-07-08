package dev.forgeide.core.engine.support;

import dev.forgeide.core.port.ScriptInvocation;
import dev.forgeide.core.port.ScriptResult;
import dev.forgeide.core.port.ScriptRunnerException;
import dev.forgeide.core.port.ScriptRunnerPort;

/** Scripted {@link ScriptRunnerPort} fixture: the test supplies the behaviour as a lambda. */
public final class FixtureScriptRunnerPort implements ScriptRunnerPort {

    @FunctionalInterface
    public interface Handler {
        ScriptResult handle(ScriptInvocation invocation) throws ScriptRunnerException;
    }

    private final Handler handler;

    public FixtureScriptRunnerPort(Handler handler) {
        this.handler = handler;
    }

    /** Always succeeds with exit code 0. */
    public static FixtureScriptRunnerPort alwaysOk() {
        return new FixtureScriptRunnerPort(inv -> new ScriptResult(0, "ok", ""));
    }

    @Override
    public ScriptResult run(ScriptInvocation invocation) throws ScriptRunnerException {
        return handler.handle(invocation);
    }
}
