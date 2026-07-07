package dev.forgeide.core;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CoreModuleTest {

    @Test
    void exposesModuleName() {
        assertThat(CoreModule.NAME).isEqualTo("core");
    }
}
