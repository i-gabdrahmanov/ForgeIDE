package dev.forgeide.runtime.agent;

import dev.forgeide.core.port.AgentInvocation;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code gigacode --experimental-hooks -p --output-format stream-json --verbose} (SD §6
 * table). GigaCode is a rebrand of the same Claude-Code-style CLI, so it shares
 * {@link ClaudeAgentRuntime}'s stream-json shape and stdin-prompt contract — only the binary
 * (and whatever flags the project configured on its {@code RuntimeBinding}, e.g.
 * {@code --experimental-hooks}) differ.
 */
public final class GigacodeAgentRuntime extends AbstractAgentRuntime {

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
