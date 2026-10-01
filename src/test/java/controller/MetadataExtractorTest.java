package controller;

import controller.datamart.SqliteMetadataRepository;
import model.Book;
import model.BookMetadata;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MetadataExtractorTest {

    @TempDir
    Path tempDir;

    @Test
    void extractsFieldsJoiningContinuationLinesAndKeepsBodyPath() throws Exception {
        String header = """
                Title: Peter Pan
                        [Peter and Wendy]
                Editor: Eric S. Raymond
                        Guy L. Steele
                Language: English
                """;
        Path bodyPath = tempDir.resolve("16.body.txt");

        try (SqliteMetadataRepository repository = new SqliteMetadataRepository(tempDir.resolve("metadata.db"))) {
            new MetadataExtractor(repository).extractAndProcess(new Book(16, header, "body"), bodyPath);

            BookMetadata saved = repository.findById(16).orElseThrow();
            assertEquals("Peter Pan [Peter and Wendy]", saved.title());
            assertEquals("Eric S. Raymond; Guy L. Steele", saved.author());
            assertEquals("English", saved.language());
            assertEquals(bodyPath, saved.bodyPath());
        }
    }

    @Test
    void usesUnknownWhenAFieldIsMissing() throws Exception {
        try (SqliteMetadataRepository repository = new SqliteMetadataRepository(tempDir.resolve("metadata.db"))) {
            new MetadataExtractor(repository).extractAndProcess(new Book(7, "Title: The Mayflower Compact\n", "body"), null);

            BookMetadata saved = repository.findById(7).orElseThrow();
            assertEquals("Unknown", saved.author());
            assertEquals("Unknown", saved.language());
        }
    }
}