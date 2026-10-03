package controller.control;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ControlRegistryTest {

    @TempDir
    Path controlDir;

    @Test
    void keepsTheStateAfterReopening() throws Exception {
        ControlRegistry registry = new ControlRegistry(controlDir);
        registry.markDownloaded(5);
        registry.markDownloaded(12);
        registry.markIndexed(5);

        ControlRegistry reopened = new ControlRegistry(controlDir);

        assertEquals(Set.of(5, 12), reopened.downloaded());
        assertEquals(Set.of(5), reopened.indexed());
        assertEquals(Set.of(12), reopened.pendingToIndex());
    }

    @Test
    void writesOneIdPerLineWithoutDuplicates() throws Exception {
        ControlRegistry registry = new ControlRegistry(controlDir);
        registry.markDownloaded(5);
        registry.markDownloaded(5);
        registry.markDownloaded(12);

        assertEquals("5\n12\n", Files.readString(controlDir.resolve("downloaded_books.txt")));
    }

    @Test
    void ignoresAnIncompleteLastLineLeftByACrash() throws Exception {
        Files.writeString(controlDir.resolve("downloaded_books.txt"), "5\n12\n13", StandardCharsets.UTF_8);

        ControlRegistry registry = new ControlRegistry(controlDir);
        registry.markDownloaded(14);

        assertEquals(Set.of(5, 12, 14), registry.downloaded());
        assertEquals("5\n12\n14\n", Files.readString(controlDir.resolve("downloaded_books.txt")));
    }

    @Test
    void ignoresBlankAndInvalidLines() throws Exception {
        Files.writeString(controlDir.resolve("indexed_books.txt"), "5\n\nabc\n 12 \n", StandardCharsets.UTF_8);

        assertEquals(Set.of(5, 12), new ControlRegistry(controlDir).indexed());
    }

    @Test
    void forgettingADownloadMakesItAvailableAgain() throws Exception {
        ControlRegistry registry = new ControlRegistry(controlDir);
        registry.markDownloaded(5);
        registry.markDownloaded(12);
        registry.forgetDownloaded(5);

        assertEquals(Set.of(12), new ControlRegistry(controlDir).downloaded());
    }
}
