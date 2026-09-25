package controller;

import model.Book;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MetadataExtractor {

    private final Pattern titlePattern = Pattern.compile("Title:\\s*(.+)");
    private final Pattern authorPattern = Pattern.compile("Author:\\s*(.+)");
    private final Pattern languagePattern = Pattern.compile("Language:\\s*(.+)");

    public void extractAndProcess(Book book) {
        String headerText = book.head();

        Matcher titleMatcher = titlePattern.matcher(headerText);
        Matcher authorMatcher = authorPattern.matcher(headerText);
        Matcher languageMatcher = languagePattern.matcher(headerText);

        String title = titleMatcher.find() ? titleMatcher.group(1).trim() : "Unknown";
        String author = authorMatcher.find() ? authorMatcher.group(1).trim() : "Unknown";
        String language = languageMatcher.find() ? languageMatcher.group(1).trim() : "Unknown";

        System.out.println("Extracted: ID=" + book.id() + " | Title=" + title + " | Author=" + author + " | Lang=" + language);

        saveToDatabase(book.id(), title, author, language);
    }

    private void saveToDatabase(int id, String title, String author, String language) {
        // Esta función está lista para conectarse a la DB de Joel.
        // De momento, solo imprimimos para verificar que funciona.
        System.out.println("Simulando guardado en DB para el libro " + id + "...");
    }
}