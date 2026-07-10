package dev.forgeide.runtime.harness;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** T20: which token in a {@code ScriptStep}/judge {@code command} list is the script file. */
class JudgeScriptLocatorTest {

    @Test
    void skipsTheInterpreterAndFindsTheScriptFile(@TempDir Path project) throws IOException {
        Files.createDirectories(project.resolve(".gigacode/skills/forgelite/scripts"));
        Files.writeString(project.resolve(".gigacode/skills/forgelite/scripts/check_tests_red.py"), "print(1)\n");

        Optional<Path> located = JudgeScriptLocator.locate(project,
                List.of("python3", ".gigacode/skills/forgelite/scripts/check_tests_red.py"));

        assertThat(located).contains(Path.of(".gigacode/skills/forgelite/scripts/check_tests_red.py"));
    }

    @Test
    void findsANonHarnessProjectScriptToo(@TempDir Path project) throws IOException {
        Files.createDirectories(project.resolve("scripts"));
        Files.writeString(project.resolve("scripts/check_coverage.py"), "print(1)\n");

        Optional<Path> located = JudgeScriptLocator.locate(project, List.of("python3", "scripts/check_coverage.py"));

        assertThat(located).contains(Path.of("scripts/check_coverage.py"));
    }

    @Test
    void skipsFlagsBeforeTheScriptToken(@TempDir Path project) throws IOException {
        Files.createDirectories(project.resolve("scripts"));
        Files.writeString(project.resolve("scripts/check.py"), "print(1)\n");

        Optional<Path> located = JudgeScriptLocator.locate(project,
                List.of("python3", "-u", "scripts/check.py", "--flag"));

        assertThat(located).contains(Path.of("scripts/check.py"));
    }

    @Test
    void emptyWhenNoTokenResolvesToAnExistingFile(@TempDir Path project) {
        Optional<Path> located = JudgeScriptLocator.locate(project, List.of("bash", "-c", "echo hi"));

        assertThat(located).isEmpty();
    }
}
