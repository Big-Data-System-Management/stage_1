package controller.datamart;

import model.BookMetadata;

import java.util.List;
import java.util.Optional;

public interface MetadataRepository extends AutoCloseable {
    void save(BookMetadata metadata);
    void saveAll(List<BookMetadata> metadata);
    Optional<BookMetadata> findById(int bookId);
    List<BookMetadata> findByAuthor(String author);
    List<BookMetadata> findByTitle(String title);
    List<BookMetadata> findByLanguage(String language);
    List<BookMetadata> findAll();
    int count();
}
