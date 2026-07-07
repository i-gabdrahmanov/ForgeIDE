package dev.forgeide.importer;

import dev.forgeide.core.CoreModule;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ImporterModuleTest {

    @Test
    void exposesModuleName() {
        assertThat(ImporterModule.NAME).isEqualTo("importer");
    }

    @Test
    void dependsOnCore() {
        assertThat(CoreModule.NAME).isEqualTo("core");
    }
}
