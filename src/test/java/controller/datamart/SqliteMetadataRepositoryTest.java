package controller.datamart;

import model.BookMetadata;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class SqliteMetadataRepositoryTest {

    @TempDir
    Path tempDir;

    private Path dbFile;
    private SqliteMetadataRepository repository;

    @BeforeEach
    void setUp() throws Exception {
        dbFile = tempDir.resolve("datamart").resolve("metadata.db");
        repository = new SqliteMetadataRepository(dbFile);
    }

    @AfterEach
    void tearDown() throws Exception {
        repository.close();
    }

    private static BookMetadata book(int id, String title, String author, String language) {
        return new BookMetadata(id, title, author, language, Path.of("datalake", id + ".body.txt"));
    }

    @Test
    void savedBookCanBeFoundById() {
        BookMetadata crusoe = book(5, "Robinson Crusoe", "Daniel Defoe", "English");

        repository.save(crusoe);

        assertEquals(Optional.of(crusoe), repository.findById(5));
    }

    @Test
    void findByIdReturnsEmptyWhenBookIsMissing() {
        assertEquals(Optional.empty(), repository.findById(999));
    }

    @Test
    void savingSameIdTwiceUpdatesInsteadOfDuplicating() {
        repository.save(book(5, "Old title", "Daniel Defoe", "English"));
        BookMetadata updated = book(5, "Robinson Crusoe", "Daniel Defoe", "English");

        repository.save(updated);

        assertEquals(1, repository.count());
        assertEquals(Optional.of(updated), repository.findById(5));
    }

    @Test
    void findByAuthorReturnsOnlyThatAuthorsBooksIgnoringCase() {
        BookMetadata pride = book(1342, "Pride and Prejudice", "Jane Austen", "English");
        BookMetadata emma = book(158, "Emma", "Jane Austen", "English");
        repository.saveAll(List.of(pride, emma, book(5, "Robinson Crusoe", "Daniel Defoe", "English")));

        assertEquals(List.of(emma, pride), repository.findByAuthor("jane austen"));
    }

    @Test
    void findByTitleMatchesIgnoringCase() {
        BookMetadata crusoe = book(5, "Robinson Crusoe", "Daniel Defoe", "English");
        repository.save(crusoe);

        assertEquals(List.of(crusoe), repository.findByTitle("robinson crusoe"));
    }

    @Test
    void findByLanguageReturnsOnlyThatLanguage() {
        BookMetadata quijote = book(2000, "Don Quijote", "Miguel de Cervantes", "Spanish");
        repository.saveAll(List.of(quijote, book(5, "Robinson Crusoe", "Daniel Defoe", "English")));

        assertEquals(List.of(quijote), repository.findByLanguage("Spanish"));
    }

    @Test
    void findAllReturnsBooksOrderedById() {
        BookMetadata a = book(3, "C", "X", "English");
        BookMetadata b = book(1, "A", "Y", "English");
        repository.saveAll(List.of(a, b));

        assertEquals(List.of(b, a), repository.findAll());
    }

    @Test
    void dataSurvivesReopeningTheDatabase() throws Exception {
        BookMetadata crusoe = book(5, "Robinson Crusoe", "Daniel Defoe", "English");
        repository.save(crusoe);
        repository.close();

        repository = new SqliteMetadataRepository(dbFile);

        assertEquals(Optional.of(crusoe), repository.findById(5));
    }
}
