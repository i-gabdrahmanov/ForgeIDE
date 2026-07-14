package dev.forgeide.runtime.harness;

import dev.forgeide.core.port.HarnessGuardPort;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;

/**
 * Real filesystem/process {@link HarnessGuardPort} (SDD FR-1.4/FR-8.3/SR-7/SR-8): deploys a
 * project's harness into the IDE's content-addressed cache, runs {@code deploy.sh}/{@code
 * preflight.py}, and detects/resolves drift against the persisted baseline ({@link
 * HarnessRegistry}). {@code forgeideHome} is injectable (default {@code ~/.forgeide}, see {@link
 * HarnessLayout#defaultForgeideHome}) so tests can point it at a throwaway directory instead of a
 * developer's real home — same split as {@code FileStateStore(Path stateRoot)}.
 */
public final class DefaultHarnessGuard implements HarnessGuardPort {

    private static final Duration PROCESS_TIMEOUT = Duration.ofSeconds(30);

    private final Path forgeideHome;

    public DefaultHarnessGuard() {
        this(HarnessLayout.defaultForgeideHome());
    }

    public DefaultHarnessGuard(Path forgeideHome) {
        this.forgeideHome = Objects.requireNonNull(forgeideHome, "forgeideHome");
    }

    @Override
    public DeployResult deploy(Path projectRoot) {
        healLayout(projectRoot);
        Map<String, String> manifest = HarnessManifest.scan(projectRoot);
        String hash = HarnessManifest.aggregateHash(manifest);
        copyIntoCache(projectRoot, manifest, HarnessLayout.cacheDir(forgeideHome, hash));

        runScript(materialize("deploy.sh"), List.of(projectRoot.toString()));
        resolveSettings(projectRoot);
        PreflightRun preflight = runPreflight(projectRoot);

        HarnessRegistry.write(forgeideHome, projectRoot, new HarnessRegistry.Entry(manifest, hash,
                preflight.passed(), preflight.output(), Instant.now()));
        return new DeployResult(hash, preflight.passed(), preflight.output());
    }

    @Override
    public PreflightStatus preflightStatus(Path projectRoot) {
        return HarnessRegistry.read(forgeideHome, projectRoot)
                .map(entry -> new PreflightStatus(entry.preflightPassed(),
                        entry.preflightPassed() ? entry.preflightOutput() : notPassedDetail(entry.preflightOutput()),
                        Optional.of(entry.deployedAt())))
                .orElseGet(() -> new PreflightStatus(false, "harness not deployed for this project", Optional.empty()));
    }

    private static String notPassedDetail(String preflightOutput) {
        return preflightOutput.isBlank() ? "preflight did not pass" : preflightOutput;
    }

    @Override
    public Optional<Drift> checkDrift(Path projectRoot) {
        Optional<HarnessRegistry.Entry> baseline = HarnessRegistry.read(forgeideHome, projectRoot);
        if (baseline.isEmpty()) {
            // No baseline to drift from — FR-1.4's preflight gate is what refuses a run in this
            // case, not SR-8's drift check.
            return Optional.empty();
        }
        Map<String, String> currentManifest = HarnessManifest.scan(projectRoot);
        String currentHash = HarnessManifest.aggregateHash(currentManifest);
        if (currentHash.equals(baseline.get().hash())) {
            return Optional.empty();
        }
        String diff = HarnessManifest.diff(baseline.get().manifest(), currentManifest);
        return Optional.of(new Drift(baseline.get().hash(), currentHash, diff));
    }

    @Override
    public void acceptDrift(Path projectRoot) {
        Optional<HarnessRegistry.Entry> baseline = HarnessRegistry.read(forgeideHome, projectRoot);
        Map<String, String> manifest = HarnessManifest.scan(projectRoot);
        String hash = HarnessManifest.aggregateHash(manifest);
        copyIntoCache(projectRoot, manifest, HarnessLayout.cacheDir(forgeideHome, hash));
        boolean preflightPassed = baseline.map(HarnessRegistry.Entry::preflightPassed).orElse(false);
        String preflightOutput = baseline.map(HarnessRegistry.Entry::preflightOutput).orElse("");
        HarnessRegistry.write(forgeideHome, projectRoot,
                new HarnessRegistry.Entry(manifest, hash, preflightPassed, preflightOutput, Instant.now()));
    }

    @Override
    public List<String> rollbackDrift(Path projectRoot) {
        Optional<HarnessRegistry.Entry> baseline = HarnessRegistry.read(forgeideHome, projectRoot);
        if (baseline.isEmpty()) {
            return List.of();
        }
        Path root = HarnessLayout.harnessRoot(projectRoot);
        Path cache = HarnessLayout.cacheDir(forgeideHome, baseline.get().hash());
        Map<String, String> currentManifest = HarnessManifest.scan(projectRoot);
        List<String> restored = new ArrayList<>();

        for (String relativePath : baseline.get().manifest().keySet()) {
            if (!baseline.get().manifest().get(relativePath).equals(currentManifest.get(relativePath))) {
                copyFile(cache.resolve(relativePath), root.resolve(relativePath));
                restored.add(relativePath);
            }
        }
        for (String relativePath : currentManifest.keySet()) {
            if (!baseline.get().manifest().containsKey(relativePath)) {
                try {
                    Files.deleteIfExists(root.resolve(relativePath));
                    restored.add(relativePath);
                } catch (IOException e) {
                    // best-effort — left for the human to remove by hand, same spirit as GitScopeDiff#rollback
                }
            }
        }
        return restored;
    }

    @Override
    public List<String> resolveFromCache(Path projectRoot, List<String> command) {
        Optional<HarnessRegistry.Entry> baseline = HarnessRegistry.read(forgeideHome, projectRoot);
        if (baseline.isEmpty()) {
            return List.copyOf(command);
        }
        Path root = HarnessLayout.harnessRoot(projectRoot);
        Path cache = HarnessLayout.cacheDir(forgeideHome, baseline.get().hash());
        List<String> resolved = new ArrayList<>(command.size());
        for (String token : command) {
            resolved.add(resolveToken(projectRoot, root, cache, baseline.get().manifest(), token));
        }
        return resolved;
    }

    private static String resolveToken(Path projectRoot, Path harnessRoot, Path cacheDir,
                                        Map<String, String> baselineManifest, String token) {
        Path resolved;
        try {
            resolved = projectRoot.resolve(token).normalize();
        } catch (RuntimeException notAPath) {
            return token;
        }
        if (!resolved.startsWith(harnessRoot)) {
            return token;
        }
        String relative = harnessRoot.relativize(resolved).toString().replace('\\', '/');
        if (!baselineManifest.containsKey(relative)) {
            return token;
        }
        return cacheDir.resolve(relative).toString();
    }

    @Override
    public HarnessEditResult edit(Path projectRoot, String relativePath, String content) {
        Path root = HarnessLayout.harnessRoot(projectRoot);
        Path target = root.resolve(relativePath);
        String oldHash = HarnessRegistry.read(forgeideHome, projectRoot).map(HarnessRegistry.Entry::hash).orElse("");
        try {
            Files.createDirectories(target.getParent());
            Files.writeString(target, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to write harness file: " + target, e);
        }
        Map<String, String> manifest = HarnessManifest.scan(projectRoot);
        String newHash = HarnessManifest.aggregateHash(manifest);
        copyIntoCache(projectRoot, manifest, HarnessLayout.cacheDir(forgeideHome, newHash));
        HarnessRegistry.write(forgeideHome, projectRoot, new HarnessRegistry.Entry(manifest, newHash, true,
                "", Instant.now()));
        return new HarnessEditResult(oldHash, newHash, "~ modified: " + relativePath + "\n");
    }

    // ---- T41: forge-native deploy (heal → resolve → preflight) ------------------------------

    /** T41 self-heal (the reverse of the old T32 move): a project deployed under the old ForgeIDE
     * convention keeps {@code settings.hooks.json} at the harness root, but forge's resolver and
     * preflight — and now ForgeIDE — look for the template under {@code hooks/}. Relocate it there
     * before anything hashes the tree, so the manifest/cache/baseline see the forge layout. No-op
     * once it already sits under {@code hooks/}, and best-effort: a failed move leaves preflight to
     * report a template that isn't where it belongs. */
    private static void healLayout(Path projectRoot) {
        Path template = HarnessLayout.templateFile(projectRoot);
        Path legacy = HarnessLayout.harnessRoot(projectRoot).resolve(HarnessLayout.TEMPLATE_FILE);
        if (Files.isRegularFile(template) || !Files.isRegularFile(legacy)) {
            return;
        }
        try {
            Files.createDirectories(template.getParent());
            Files.move(legacy, template);
        } catch (IOException e) {
            // best-effort — preflight still names the misplaced template with a fix hint.
        }
    }

    /** T41: generate the resolved {@code .gigacode/settings.json} the agent runtime (gigacode)
     * reads. Prefer the harness's own resolver ({@code hooks/resolve_hook_paths.py}) so ForgeIDE
     * behaves exactly like a forge deploy — same {@code ${PROJECT_ROOT}}/{@code ${PYTHON}}
     * expansion, same output — and fall back to the bundled {@code resolve.py} for harnesses that
     * ship none. */
    private void resolveSettings(Path projectRoot) {
        Path resolver = HarnessLayout.resolverScript(projectRoot);
        if (Files.isRegularFile(resolver)) {
            runScript(resolver, List.of("--project", projectRoot.toString()));
        } else {
            runScript(materialize("resolve.py"), List.of(projectRoot.toString()));
        }
    }

    /** T41: validate with the harness's own preflight ({@code hooks/preflight.py}) when present,
     * else the bundled one. The harness preflight's exit codes carry more than pass/fail: 0 = ok,
     * 2 = deployed but the project isn't initialized yet (enforcement is still on — a fresh deploy
     * is expected to land here, so it counts as passed, preserving T37's "fresh project deploys
     * clean"), 1 = enforcement off. */
    private PreflightRun runPreflight(Path projectRoot) {
        Path harnessPreflight = HarnessLayout.harnessPreflight(projectRoot);
        if (Files.isRegularFile(harnessPreflight)) {
            ProcessResult result = runScript(harnessPreflight, List.of("--project", projectRoot.toString()));
            return new PreflightRun(result.exitCode() == 0 || result.exitCode() == 2, result.output());
        }
        ProcessResult result = runScript(materialize("preflight.py"), List.of(projectRoot.toString()));
        return new PreflightRun(result.exitCode() == 0, result.output());
    }

    private record PreflightRun(boolean passed, String output) {
    }

    // ---- cache I/O --------------------------------------------------------------------------

    private static void copyIntoCache(Path projectRoot, Map<String, String> manifest, Path cacheDir) {
        Path harnessRoot = HarnessLayout.harnessRoot(projectRoot);
        for (String relativePath : new TreeMap<>(manifest).keySet()) {
            copyFile(harnessRoot.resolve(relativePath), cacheDir.resolve(relativePath));
        }
    }

    private static void copyFile(Path source, Path target) {
        try {
            Files.createDirectories(target.getParent());
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to copy harness file " + source + " -> " + target, e);
        }
    }

    // ---- bundled deploy.sh/preflight.py ------------------------------------------------------

    /** Materializes the bundled wrapper resource next to the running IDE (idempotent — always
     * refreshed so a newer IDE build's wrapper takes effect on the next deploy). */
    private Path materialize(String resourceName) {
        Path target = HarnessLayout.binDir(forgeideHome).resolve(resourceName);
        try (InputStream in = DefaultHarnessGuard.class.getResourceAsStream(resourceName)) {
            if (in == null) {
                throw new IllegalStateException("bundled harness wrapper not found on classpath: " + resourceName);
            }
            Files.createDirectories(target.getParent());
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            target.toFile().setExecutable(true);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to materialize " + resourceName, e);
        }
        return target;
    }

    private static ProcessResult runScript(Path script, List<String> args) {
        List<String> command = new ArrayList<>();
        if (script.toString().endsWith(".py")) {
            command.add("python3");
        } else {
            command.add("bash");
        }
        command.add(script.toString());
        command.addAll(args);

        Process process;
        try {
            process = new ProcessBuilder(command)
                    .directory(script.getParent().toFile())
                    .redirectErrorStream(true)
                    .start();
        } catch (IOException e) {
            return new ProcessResult(1, "failed to start " + script.getFileName() + ": " + e.getMessage());
        }

        Thread watchdog = Thread.ofVirtual().start(() -> {
            try {
                if (!process.waitFor(PROCESS_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                    process.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        try {
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            process.waitFor();
            return new ProcessResult(process.exitValue(), output.strip());
        } catch (IOException e) {
            return new ProcessResult(1, String.valueOf(e.getMessage()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new ProcessResult(1, "interrupted");
        } finally {
            watchdog.interrupt();
            process.destroyForcibly();
        }
    }

    private record ProcessResult(int exitCode, String output) {
    }
}
