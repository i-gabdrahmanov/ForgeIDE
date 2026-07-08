package dev.forgeide.ui.project;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FlagsTextTest {

    @Test
    void parsesSpaceSeparatedFlags() {
        assertThat(FlagsText.parse("--experimental-hooks --foo bar"))
                .containsExactly("--experimental-hooks", "--foo", "bar");
    }

    @Test
    void collapsesExtraWhitespace() {
        assertThat(FlagsText.parse("  --a   --b  ")).containsExactly("--a", "--b");
    }

    @Test
    void blankOrNullYieldsEmptyList() {
        assertThat(FlagsText.parse("")).isEmpty();
        assertThat(FlagsText.parse("   ")).isEmpty();
        assertThat(FlagsText.parse(null)).isEmpty();
    }

    @Test
    void formatJoinsWithSpaces() {
        assertThat(FlagsText.format(List.of("--a", "--b"))).isEqualTo("--a --b");
    }

    @Test
    void roundTrips() {
        List<String> flags = List.of("--experimental-hooks", "--max-tokens=100");
        assertThat(FlagsText.parse(FlagsText.format(flags))).isEqualTo(flags);
    }
}
