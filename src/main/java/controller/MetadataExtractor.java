package controller;

import controller.datamart.MetadataRepository;
import model.Book;
import model.BookMetadata;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MetadataExtractor {

    private static final String UNKNOWN = "Unknown";
    private static final Pattern TITLE = field("Title");
    private static final Pattern LANGUAGE = field("Language");
    private static final List<Pattern> AUTHOR_FIELDS = List.of(
            field("Author"), field("Editor"), field("Translator"), field("Compiler"));

    private final MetadataRepository repository;

    public MetadataExtractor(MetadataRepository repository) {
        this.repository = repository;
    }

    public void extractAndProcess(Book book, Path bodyPath) {
        String header = book.head();
        String title = find(TITLE, header, " ").orElse(UNKNOWN);
        String author = findAuthor(header).orElse(UNKNOWN);
        String language = find(LANGUAGE, header, " ").orElse(UNKNOWN);
        repository.save(new BookMetadata(book.id(), title, author, language, bodyPath));
    }

    private static Optional<String> findAuthor(String header) {
        return AUTHOR_FIELDS.stream()
                .map(pattern -> find(pattern, header, "; "))
                .flatMap(Optional::stream)
                .findFirst();
    }

    private static Pattern field(String name) {
        return Pattern.compile("^" + name + ":[ \\t]*(.+(?:\\R[ \\t]+.+)*)", Pattern.MULTILINE);
    }

    private static Optional<String> find(Pattern pattern, String header, String lineSeparator) {
        Matcher matcher = pattern.matcher(header);
        if (!matcher.find()) return Optional.empty();
        String value = matcher.group(1)
                .replaceAll("\\R[ \\t]+", lineSeparator)
                .replaceAll("[ \\t]+", " ")
                .strip();
        return value.isEmpty() ? Optional.empty() : Optional.of(value);
    }
}