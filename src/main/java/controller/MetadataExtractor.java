package controller;

import controller.datamart.MetadataRepository;
import model.BookMetadata;
import java.nio.file.Path;
import model.Book;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MetadataExtractor {
    private final MetadataRepository repository;
    private final Pattern titlePattern = Pattern.compile("Title:\\s*(.+)");
    private final Pattern authorPattern = Pattern.compile("Author:\\s*(.+)");
    private final Pattern languagePattern = Pattern.compile("Language:\\s*(.+)");

    public MetadataExtractor(MetadataRepository repository) {
        this.repository = repository;
    }

    public void extractAndProcess(Book book) {
        String headerText = book.head();

        Matcher titleMatcher = titlePattern.matcher(headerText);
        Matcher authorMatcher = authorPattern.matcher(headerText);
        Matcher languageMatcher = languagePattern.matcher(headerText);

        String title = titleMatcher.find() ? titleMatcher.group(1).trim() : "Unknown";
        String author = authorMatcher.find() ? authorMatcher.group(1).trim() : "Unknown";
        String language = languageMatcher.find() ? languageMatcher.group(1).trim() : "Unknown";

        System.out.println("Extracted: ID=" + book.id() + " | Title=" + title + " | Author=" + author + " | Lang=" + language);

        saveToDatabase(book.id(), title, author, language, null);
    }

    private void saveToDatabase(int id, String title, String author, String language, Path bodyPath) {
        BookMetadata metadata = new BookMetadata(id, title, author, language, bodyPath);
        repository.save(metadata);
    }
}