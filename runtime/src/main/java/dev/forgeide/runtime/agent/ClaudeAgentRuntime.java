package dev.forgeide.runtime.agent;

import dev.forgeide.core.port.AgentInvocation;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code claude -p --output-format stream-json --verbose} (SD §6 table) — the prompt travels
 * via stdin, so {@code -p} is given with no positional argument.
 */
public final class ClaudeAgentRuntime extends AbstractAgentRuntime {

    @Override
    protected List<String> buildCommand(AgentInvocation invocation) {
        List<String> command = new ArrayList<>();
        command.add(invocation.runtime().binaryPath().toString());
        command.addAll(invocation.runtime().flags());
        command.add("-p");
        command.add("--output-format");
        command.add("stream-json");
        command.add("--verbose");
        return command;
    }
}
