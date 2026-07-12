package dev.forgeide.core.secret;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T27 acceptance: exact secret values (env_scope resolutions) and typical credential shapes
 * (Bearer/token=/provider-token) are both masked, in text that also has ordinary content around
 * them; blank input/values are left alone rather than turned into a wall of {@code ***}.
 */
class SecretMaskerTest {

    @Test
    void masksAnExactKnownSecretValueWhereverItAppears() {
        String text = "check failed: expected token s3cr3t-value but saw s3cr3t-value twice";

        String masked = SecretMasker.mask(text, List.of("s3cr3t-value"));

        assertThat(masked).doesNotContain("s3cr3t-value")
                .isEqualTo("check failed: expected token *** but saw *** twice");
    }

    @Test
    void masksABearerTokenPatternEvenWithNoKnownValue() {
        String masked = SecretMasker.mask("sending header Authorization: Bearer abc.def-123XYZ now", Set.of());

        assertThat(masked).isEqualTo("sending header Authorization: Bearer *** now");
    }

    @Test
    void masksATokenAssignmentPattern() {
        String masked = SecretMasker.mask("push failed: token=ghs_notArealTokenButShaped123", Set.of());

        assertThat(masked).isEqualTo("push failed: token=***");
    }

    @Test
    void masksGithubAndGitlabStyleTokens() {
        assertThat(SecretMasker.mask("leaked ghp_abcdefghijklmnopqrstuvwxyz012345", Set.of()))
                .isEqualTo("leaked ***");
        assertThat(SecretMasker.mask("leaked glpat-abcdefghijklmnopqrst", Set.of()))
                .isEqualTo("leaked ***");
    }

    @Test
    void nullAndBlankInputsPassThroughUntouched() {
        assertThat(SecretMasker.mask(null, List.of("x"))).isNull();
        assertThat(SecretMasker.mask("", List.of("x"))).isEmpty();
        assertThat(SecretMasker.mask("hello world", Arrays.asList("", "   ", null))).isEqualTo("hello world");
    }
}
