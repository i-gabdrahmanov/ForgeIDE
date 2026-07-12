package dev.forgeide.core.engine.support;

import dev.forgeide.core.port.HarnessGuardPort;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.UnaryOperator;

/** Scripted {@link HarnessGuardPort} fixture: every field is mutable/volatile so a test can flip
 * behaviour (e.g. arm drift after a step completes) from the test thread while the engine's actor
 * and worker threads read it. */
public final class FixtureHarnessGuardPort implements HarnessGuardPort {

    public volatile boolean preflightPassed = true;
    public volatile String preflightDetail = "";
    public volatile boolean drifted = false;
    public volatile Drift drift = new Drift("baseline-hash", "current-hash", "~ modified: hooks/tdd-guard.py\n");
    public volatile UnaryOperator<List<String>> cacheResolver = List::copyOf;

    public final AtomicInteger acceptCalls = new AtomicInteger();
    public final AtomicInteger rollbackCalls = new AtomicInteger();
    public final List<List<String>> resolvedCommands = new CopyOnWriteArrayList<>();
    public final List<EditCall> editCalls = new CopyOnWriteArrayList<>();

    /** T20/FR-8.3: what a {@code HarnessGuardPort#edit} caller actually asked for. */
    public record EditCall(Path projectRoot, String relativePath, String content) {
    }

    @Override
    public DeployResult deploy(Path projectRoot) {
        return new DeployResult("current-hash", preflightPassed, preflightDetail);
    }

    @Override
    public PreflightStatus preflightStatus(Path projectRoot) {
        return new PreflightStatus(preflightPassed, preflightDetail, Optional.empty());
    }

    @Override
    public Optional<Drift> checkDrift(Path projectRoot) {
        return drifted ? Optional.of(drift) : Optional.empty();
    }

    @Override
    public void acceptDrift(Path projectRoot) {
        acceptCalls.incrementAndGet();
        drifted = false;
    }

    @Override
    public List<String> rollbackDrift(Path projectRoot) {
        rollbackCalls.incrementAndGet();
        drifted = false;
        return List.of("hooks/tdd-guard.py");
    }

    @Override
    public List<String> resolveFromCache(Path projectRoot, List<String> command) {
        List<String> resolved = cacheResolver.apply(command);
        resolvedCommands.add(resolved);
        return resolved;
    }

    @Override
    public HarnessEditResult edit(Path projectRoot, String relativePath, String content) {
        editCalls.add(new EditCall(projectRoot, relativePath, content));
        return new HarnessEditResult("old-hash", "new-hash", "~ modified: " + relativePath + "\n");
    }
}
