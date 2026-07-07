package dev.forgeide.ui;

import dev.forgeide.core.CoreModule;
import dev.forgeide.importer.ImporterModule;
import dev.forgeide.runtime.RuntimeModule;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UiModuleTest {

    @Test
    void allUpstreamModulesAreOnClasspath() {
        assertThat(CoreModule.NAME).isEqualTo("core");
        assertThat(RuntimeModule.NAME).isEqualTo("runtime");
        assertThat(ImporterModule.NAME).isEqualTo("importer");
    }
}
