package dev.forgeide.importer;

import dev.forgeide.core.pipeline.TileValidityStatus;
import dev.forgeide.core.pipeline.yaml.PipelineTemplates;
import dev.forgeide.core.port.TileValidityChecker;
import dev.forgeide.importer.scaffold.ScaffoldCatalog;
import dev.forgeide.importer.scaffold.ScaffoldScanner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;

class ProjectValidityCheckersTest {

    @Test
    void unknownWhenProjectWasNeverImported(@TempDir Path projectRoot) {
        assertThat(ProjectValidityCheckers.load(projectRoot).check(PipelineTemplates.forgelite().step("lite-ground")))
                .isEqualTo(dev.forgeide.core.pipeline.TileValidity.unknown());
    }

    @Test
    void rebuildsARealCheckerFromWhatImportWriterLeftOnDisk(@TempDir Path projectRoot) {
        ScaffoldCatalog catalog = ScaffoldScanner.scan(fixture("sample-scaffold"));
        ImportSession session = new ImportSession(PipelineTemplates.forgelite(), catalog);
        ImportWriter.write(projectRoot, session.result());

        TileValidityChecker checker = ProjectValidityCheckers.load(projectRoot);

        assertThat(checker.check(PipelineTemplates.forgelite().step("lite-ground")).status())
                .isEqualTo(TileValidityStatus.FRESH);
    }

    private static Path fixture(String name) {
        try {
            return Paths.get(ProjectValidityCheckersTest.class
                    .getResource("/dev/forgeide/importer/fixtures/" + name).toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }
}
