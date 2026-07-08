package dev.forgeide.runtime.agent;

import dev.forgeide.core.port.AgentInvocation;

import java.util.ArrayList;
import java.util.List;

/**
 * Test-only {@link AbstractAgentRuntime} whose "CLI" is just {@code invocation.runtime()}'s
 * binaryPath+flags verbatim — lets tests replay a checked-in stream-json fixture (e.g.
 * {@code /bin/sh -c "cat fixture.jsonl"}) through the exact same execution path
 * {@link ClaudeAgentRuntime}/{@link GigacodeAgentRuntime} use, without needing a real CLI.
 */
final class FixtureReplayAgentRuntime extends AbstractAgentRuntime {

    @Override
    protected List<String> buildCommand(AgentInvocation invocation) {
        List<String> command = new ArrayList<>();
        command.add(invocation.runtime().binaryPath().toString());
        command.addAll(invocation.runtime().flags());
        return command;
    }
}
