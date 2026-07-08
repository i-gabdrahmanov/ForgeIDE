package dev.forgeide.core.engine.support;

import dev.forgeide.core.port.AgentEvent;
import dev.forgeide.core.port.AgentInvocation;
import dev.forgeide.core.port.AgentResult;
import dev.forgeide.core.port.AgentRuntimeException;
import dev.forgeide.core.port.AgentRuntimePort;

import java.util.function.Consumer;

/** Scripted {@link AgentRuntimePort} fixture: the test supplies the behaviour as a lambda. */
public final class FixtureAgentRuntimePort implements AgentRuntimePort {

    @FunctionalInterface
    public interface Handler {
        AgentResult handle(AgentInvocation invocation) throws AgentRuntimeException;
    }

    private final Handler handler;

    public FixtureAgentRuntimePort(Handler handler) {
        this.handler = handler;
    }

    @Override
    public AgentResult execute(AgentInvocation invocation, Consumer<AgentEvent> onEvent) throws AgentRuntimeException {
        return handler.handle(invocation);
    }
}
