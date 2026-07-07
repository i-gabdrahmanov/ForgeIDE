package dev.forgeide.core.port;

import java.util.function.Consumer;

/**
 * Adapter contract for a headless agent CLI (claude/qwen/gigacode) (SD §6).
 * Implemented in {@code runtime}.
 */
public interface AgentRuntimePort {
    AgentResult execute(AgentInvocation invocation, Consumer<AgentEvent> onEvent) throws AgentRuntimeException;
}
