package dev.forgeide.core;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

class ArchitectureRulesTest {

    private static final JavaClasses CORE_CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("dev.forgeide.core");

    @Test
    void coreMustNotDependOnJavaFx() {
        noClasses()
                .should().dependOnClassesThat().resideInAPackage("javafx..")
                .check(CORE_CLASSES);
    }

    @Test
    void coreMustNotUseProcessBuilder() {
        noClasses()
                .should().dependOnClassesThat().belongToAnyOf(ProcessBuilder.class)
                .check(CORE_CLASSES);
    }
}
