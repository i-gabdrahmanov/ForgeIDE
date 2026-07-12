package dev.forgeide.runtime.process;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class ProcessRunnerTest {

    private final ProcessRunner runner = new ProcessRunner();

    @Test
    void runParsesJsonLinesAndTreatsGarbageAsRawTolerantly(@TempDir Path dir) {
        List<String> command = List.of("/bin/sh", "-c",
                "echo '{\"type\":\"result\",\"ok\":true}'; echo 'not json at all'");
        ProcessSpec spec = spec(dir, command, Duration.ofSeconds(5), ProcessRunner.DEFAULT_MAX_OUTPUT_BYTES);
        List<ParsedLine> lines = new CopyOnWriteArrayList<>();

        ProcessOutcome outcome = runner.run(spec, lines::add, line -> { });

        assertThat(outcome.exitCode()).isZero();
        assertThat(outcome.timedOut()).isFalse();
        assertThat(outcome.outputCapExceeded()).isFalse();
        assertThat(outcome.pgid()).isPresent();
        assertThat(lines).hasSize(2);
        assertThat(lines.get(0)).isInstanceOfSatisfying(ParsedLine.Json.class,
                json -> assertThat(json.node().get("ok").asBoolean()).isTrue());
        assertThat(lines.get(1)).isEqualTo(new ParsedLine.Raw("not json at all"));
    }

    @Test
    void stdinIsDeliveredThenClosedAndOnlyExplicitEnvIsVisible(@TempDir Path dir) {
        List<String> command = List.of("/bin/sh", "-c",
                "cat; echo \"FOO=$FOO\"; echo \"HOME_SET=${HOME:-unset}\"");
        ProcessSpec spec = new ProcessSpec(dir, command, Map.of("FOO", "bar"),
                Optional.of("hello-from-stdin\n"), Duration.ofSeconds(5),
                ProcessRunner.DEFAULT_MAX_OUTPUT_BYTES, dir.resolve("stdout.log"), dir.resolve("stderr.log"));
        List<String> lines = new CopyOnWriteArrayList<>();

        ProcessOutcome outcome = runner.run(spec, line -> lines.add(rawText(line)), l -> { });

        assertThat(outcome.exitCode()).isZero();
        assertThat(lines).containsExactly("hello-from-stdin", "FOO=bar", "HOME_SET=unset");
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void bothStreamsOver64KbConcurrentlyDoNotDeadlock(@TempDir Path dir) {
        String line = "b".repeat(10_000);
        String script = "yes '" + line + "' | head -c 100000 >&2 & "
                + "yes '" + line + "' | head -c 100000; wait";
        ProcessSpec spec = spec(dir, List.of("/bin/sh", "-c", script),
                Duration.ofSeconds(8), ProcessRunner.DEFAULT_MAX_OUTPUT_BYTES);
        AtomicLong stdoutBytes = new AtomicLong();
        AtomicLong stderrBytes = new AtomicLong();

        ProcessOutcome outcome = runner.run(spec,
                l -> stdoutBytes.addAndGet(rawText(l).length() + 1L),
                l -> stderrBytes.addAndGet(l.length() + 1L));

        assertThat(outcome.timedOut()).isFalse();
        assertThat(outcome.outputCapExceeded()).isFalse();
        assertThat(outcome.exitCode()).isZero();
        assertThat(stdoutBytes.get()).isGreaterThanOrEqualTo(100_000);
        assertThat(stderrBytes.get()).isGreaterThanOrEqualTo(100_000);
    }

    @Test
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void wallClockTimeoutKillsProcessGroup(@TempDir Path dir) {
        ProcessSpec spec = spec(dir, List.of("/bin/sh", "-c", "sleep 30"),
                Duration.ofMillis(300), ProcessRunner.DEFAULT_MAX_OUTPUT_BYTES);

        ProcessOutcome outcome = runner.run(spec, l -> { }, l -> { });

        assertThat(outcome.timedOut()).isTrue();
        assertThat(outcome.outputCapExceeded()).isFalse();
        assertThat(outcome.exitCode()).isNotZero();
        assertThat(outcome.wallClock()).isLessThan(Duration.ofSeconds(10));
    }

    @Test
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void outputCapKillsProcessGroupBeforeTimeout(@TempDir Path dir) {
        ProcessSpec spec = spec(dir, List.of("/bin/sh", "-c", "while true; do printf '0123456789\\n'; done"),
                Duration.ofSeconds(30), 2048);

        ProcessOutcome outcome = runner.run(spec, l -> { }, l -> { });

        assertThat(outcome.outputCapExceeded()).isTrue();
        assertThat(outcome.timedOut()).isFalse();
        assertThat(outcome.wallClock()).isLessThan(Duration.ofSeconds(10));
    }

    @Test
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void nohupGrandchildIsDeadAfterKillOrCaughtBySweep(@TempDir Path dir) throws Exception {
        Path pidFile = dir.resolve("grandchild.pid");
        String script = "nohup sh -c 'cd \"" + dir + "\" && sleep 60' > /dev/null 2>&1 & "
                + "printf '%s' $! > \"" + pidFile + "\"; "
                + "sleep 30";
        ProcessSpec spec = spec(dir, List.of("/bin/sh", "-c", script),
                Duration.ofMillis(500), ProcessRunner.DEFAULT_MAX_OUTPUT_BYTES);

        ProcessOutcome outcome = runner.run(spec, l -> { }, l -> { });
        assertThat(outcome.timedOut()).isTrue();

        awaitTrue(Duration.ofSeconds(5), () -> java.nio.file.Files.exists(pidFile)
                && !readQuietly(pidFile).isBlank());
        long grandchildPid = Long.parseLong(readQuietly(pidFile).trim());

        boolean stillAlive = ProcessHandle.of(grandchildPid).map(ProcessHandle::isAlive).orElse(false);
        if (stillAlive) {
            List<Long> swept = runner.sweepOrphans(dir);
            assertThat(swept).contains(grandchildPid);
        }
        awaitTrue(Duration.ofSeconds(5),
                () -> !ProcessHandle.of(grandchildPid).map(ProcessHandle::isAlive).orElse(false));
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void sweepOrphansKillsProcessWithCwdUnderProject(@TempDir Path dir) throws Exception {
        Process orphan = new ProcessBuilder("/bin/sh", "-c", "sleep 30")
                .directory(dir.toFile())
                .start();
        try {
            assertThat(orphan.isAlive()).isTrue();

            List<Long> swept = runner.sweepOrphans(dir);

            assertThat(swept).contains(orphan.pid());
            awaitTrue(Duration.ofSeconds(5), () -> !orphan.isAlive());
        } finally {
            orphan.destroyForcibly();
        }
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void pumpThroughputMeetsTenMegabytesPerSecond(@TempDir Path dir) {
        long targetBytes = 100L * 1024 * 1024;
        String script = "S=$(printf '%*s' 65536 '' | tr ' ' 'a'); yes \"$S\" | head -c " + targetBytes;
        ProcessSpec spec = spec(dir, List.of("/bin/sh", "-c", script),
                Duration.ofSeconds(25), targetBytes * 2);
        AtomicLong received = new AtomicLong();

        ProcessOutcome outcome = runner.run(spec, l -> received.addAndGet(rawText(l).length() + 1L), l -> { });

        assertThat(outcome.outputCapExceeded()).isFalse();
        assertThat(outcome.timedOut()).isFalse();
        double seconds = outcome.wallClock().toNanos() / 1_000_000_000.0;
        double bytesPerSecond = received.get() / seconds;
        assertThat(bytesPerSecond).isGreaterThanOrEqualTo(10.0 * 1024 * 1024);
    }

    @Test
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void consumerThrowingProcessKillSignalKillsTheProcessGroupImmediately(@TempDir Path dir) {
        ProcessSpec spec = spec(dir, List.of("/bin/sh", "-c", "echo one; sleep 30; echo two"),
                Duration.ofSeconds(30), ProcessRunner.DEFAULT_MAX_OUTPUT_BYTES);
        List<ParsedLine> lines = new CopyOnWriteArrayList<>();

        ProcessOutcome outcome = runner.run(spec, line -> {
            lines.add(line);
            throw new ProcessKillSignal("budget exceeded");
        }, l -> { });

        assertThat(outcome.timedOut()).isFalse();
        assertThat(outcome.exitCode()).isNotZero();
        assertThat(outcome.wallClock()).isLessThan(Duration.ofSeconds(10));
        assertThat(lines).hasSize(1);
    }

    @Test
    void phaseSandboxWrapRewritesTheCommandBeforeLaunch(@TempDir Path dir) {
        ProcessSpec original = spec(dir, List.of("/bin/sh", "-c", "echo original"),
                Duration.ofSeconds(5), ProcessRunner.DEFAULT_MAX_OUTPUT_BYTES);
        PhaseSandbox fixture = s -> new ProcessSpec(s.workingDir(), List.of("/bin/sh", "-c", "echo wrapped"),
                s.env(), s.stdin(), s.timeout(), s.maxOutputBytes(), s.stdoutLog(), s.stderrLog());
        ProcessRunner sandboxedRunner = new ProcessRunner(fixture);
        List<ParsedLine> lines = new CopyOnWriteArrayList<>();

        ProcessOutcome outcome = sandboxedRunner.run(original, lines::add, l -> { });

        assertThat(outcome.exitCode()).isZero();
        assertThat(lines).hasSize(1);
        assertThat(rawText(lines.get(0))).isEqualTo("wrapped");
    }

    private static String rawText(ParsedLine line) {
        if (line instanceof ParsedLine.Raw raw) {
            return raw.line();
        }
        if (line instanceof ParsedLine.Json json) {
            return json.node().toString();
        }
        throw new IllegalStateException("unknown ParsedLine variant: " + line);
    }

    private static ProcessSpec spec(Path dir, List<String> command, Duration timeout, long maxOutputBytes) {
        return new ProcessSpec(dir, command, Map.of(), Optional.empty(), timeout, maxOutputBytes,
                dir.resolve("stdout.log"), dir.resolve("stderr.log"));
    }

    private static String readQuietly(Path file) {
        try {
            return java.nio.file.Files.readString(file);
        } catch (java.io.IOException e) {
            return "";
        }
    }

    private static void awaitTrue(Duration timeout, java.util.function.BooleanSupplier condition) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("condition not met within " + timeout);
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
        }
    }
}
