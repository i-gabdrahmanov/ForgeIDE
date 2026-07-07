package dev.forgeide.runtime;

import dev.forgeide.core.CoreModule;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RuntimeModuleTest {

    @Test
    void exposesModuleName() {
        assertThat(RuntimeModule.NAME).isEqualTo("runtime");
    }

    @Test
    void dependsOnCore() {
        assertThat(CoreModule.NAME).isEqualTo("core");
    }
}
