package dev.forgeide.core.vars;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

class VariablesTest {

    @Test
    void locatesReferencesInOrder() {
        var refs = Variables.references("docs/${feature.slug}/x-${params.jira_key}.md");

        assertThat(refs)
                .extracting(VariableReference::scope, VariableReference::key, VariableReference::raw)
                .containsExactly(
                        tuple("feature", "slug", "${feature.slug}"),
                        tuple("params", "jira_key", "${params.jira_key}"));
    }

    @Test
    void unknownScopeIsDetectableButStillParsed() {
        var refs = Variables.references("${bogus.name}");

        assertThat(refs).singleElement()
                .satisfies(ref -> assertThat(ref.hasKnownScope()).isFalse());
    }

    @Test
    void rendersAllReferences() {
        VariableResolver resolver = MapVariableResolver.builder()
                .feature("slug", "add-login")
                .param("jira_key", "AB-12")
                .build();

        String rendered = resolver.render("docs/${feature.slug}/${params.jira_key}.md");

        assertThat(rendered).isEqualTo("docs/add-login/AB-12.md");
    }

    @Test
    void renderThrowsOnUnresolvedReference() {
        VariableResolver resolver = MapVariableResolver.builder().build();

        assertThatThrownBy(() -> resolver.render("${feature.slug}"))
                .isInstanceOf(UnresolvedVariableException.class)
                .satisfies(ex -> assertThat(((UnresolvedVariableException) ex).reference().key()).isEqualTo("slug"));
    }

    @Test
    void textWithoutReferencesIsUnchanged() {
        assertThat(Variables.hasReferences("plain/path.md")).isFalse();
        assertThat(MapVariableResolver.builder().build().render("plain/path.md")).isEqualTo("plain/path.md");
    }
}
