package controller.index;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileIndexFormatTest {

    @TempDir
    Path tempDir;

    private final Tokenizer tokenizer = new Tokenizer();

    @Test
    void monolithicIndexWritesSortedCompactJson() throws Exception {
        Path file = tempDir.resolve("datamarts").resolve("inverted_index.json");
        InvertedIndex index = new MonolithicJsonIndex(file, tokenizer);
        index.indexBook(12, "island adventure");
        index.indexBook(5, "adventure");
        index.flush();

        assertEquals("{\"adventure\":[5,12],\"island\":[12]}", Files.readString(file));
        assertFalse(Files.exists(file.resolveSibling("inverted_index.json.tmp")));
    }

    @Test
    void folderIndexWritesOneFilePerTermGroupedByFirstLetter() throws Exception {
        Path root = tempDir.resolve("inverted_index");
        InvertedIndex index = new HierarchicalFolderIndex(root, tokenizer);
        index.indexBook(12, "adventure 1813");
        index.indexBook(5, "adventure");
        index.flush();

        assertEquals("5\n12\n", Files.readString(root.resolve("A").resolve("adventure.txt")));
        assertEquals("12\n", Files.readString(root.resolve("1").resolve("1813.txt")));
    }

    @Test
    void folderIndexPrefixesWindowsReservedNames() throws Exception {
        Path root = tempDir.resolve("inverted_index");
        InvertedIndex index = new HierarchicalFolderIndex(root, tokenizer);
        index.indexBook(1, "café con leche");
        index.flush();

        assertTrue(Files.exists(root.resolve("C").resolve("_con.txt")));
        assertTrue(Files.exists(root.resolve("C").resolve("cafe.txt")));
        assertEquals(Set.of(1), index.search("con"));
    }
}
