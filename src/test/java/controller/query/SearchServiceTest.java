package controller.query;

import controller.datamart.SqliteMetadataRepository;
import controller.index.InvertedIndex;
import controller.index.MonolithicJsonIndex;
import controller.index.Tokenizer;
import model.BookMetadata;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SearchServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void returnsTheMetadataOfEveryBookContainingTheTerm() throws Exception {
        try (SqliteMetadataRepository metadata = metadata()) {
            SearchService service = new SearchService(index(), metadata);

            List<BookMetadata> results = service.search("Whale");

            assertEquals(List.of(5, 2701), results.stream().map(BookMetadata::bookId).toList());
            assertEquals("Moby Dick", results.get(1).title());
        }
    }

    @Test
    void usesUnknownForBooksWithoutMetadata() throws Exception {
        try (SqliteMetadataRepository metadata = metadata()) {
            List<BookMetadata> results = new SearchService(index(), metadata).search("island");

            BookMetadata withoutMetadata = results.stream().filter(book -> book.bookId() == 99).findFirst().orElseThrow();
            assertEquals("Unknown", withoutMetadata.title());
        }
    }

    @Test
    void returnsNothingForUnknownTermsOrStopwords() throws Exception {
        try (SqliteMetadataRepository metadata = metadata()) {
            SearchService service = new SearchService(index(), metadata);

            assertTrue(service.search("dragon").isEmpty());
            assertTrue(service.search("the").isEmpty());
        }
    }

    private InvertedIndex index() {
        InvertedIndex index = new MonolithicJsonIndex(tempDir.resolve("inverted_index.json"), new Tokenizer());
        index.indexBook(2701, "Call me Ishmael. The whale.");
        index.indexBook(5, "A whale on an island.");
        index.indexBook(99, "Another island.");
        return index;
    }

    private SqliteMetadataRepository metadata() throws Exception {
        SqliteMetadataRepository metadata = new SqliteMetadataRepository(tempDir.resolve("metadata.db"));
        metadata.save(new BookMetadata(2701, "Moby Dick", "Herman Melville", "English", Path.of("datalake", "2701.body.txt")));
        metadata.save(new BookMetadata(5, "The Island", "Someone", "English", Path.of("datalake", "5.body.txt")));
        return metadata;
    }
}
