package dev.forgeide.runtime.agent;

import dev.forgeide.core.port.AgentEvent;
import dev.forgeide.core.port.AgentInvocation;
import dev.forgeide.core.port.AgentResult;
import dev.forgeide.core.port.AgentRuntimeException;
import dev.forgeide.core.port.AgentRuntimePort;

import java.util.Map;
import java.util.function.Consumer;

/**
 * Routes to the adapter named by {@code invocation.runtime().name()} — this is the single
 * {@link AgentRuntimePort} {@code PipelineEngine} is constructed with; it lets one pipeline
 * mix {@code runtime: claude} and {@code runtime: gigacode} steps against a single engine
 * instance, since {@code RuntimeBinding} (and therefore the binary/flags to invoke) travels
 * on every {@link AgentInvocation} rather than being fixed at construction time.
 */
public final class CompositeAgentRuntime implements AgentRuntimePort {

    private final Map<String, AgentRuntimePort> byName;

    public CompositeAgentRuntime(Map<String, AgentRuntimePort> byName) {
        this.byName = Map.copyOf(byName);
    }

    /** The two adapters wired for real use — stateless, so one instance of each suffices for
     * every project regardless of where its {@code claude}/{@code gigacode} binaries live. */
    public static CompositeAgentRuntime claudeAndGigacode() {
        return new CompositeAgentRuntime(Map.of(
                "claude", new ClaudeAgentRuntime(),
                "gigacode", new GigacodeAgentRuntime()));
    }

    @Override
    public AgentResult execute(AgentInvocation invocation, Consumer<AgentEvent> onEvent)
            throws AgentRuntimeException {
        AgentRuntimePort delegate = byName.get(invocation.runtime().name());
        if (delegate == null) {
            throw new AgentRuntimeException("no adapter registered for runtime '"
                    + invocation.runtime().name() + "'");
        }
        return delegate.execute(invocation, onEvent);
    }
}
