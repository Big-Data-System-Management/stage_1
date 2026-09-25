package controller.feeder.gutenberg;

import controller.feeder.BookFeeder;
import model.Book;
import model.RawBook;

import java.util.function.Consumer;
import java.util.regex.Pattern;

public class GutenbergBookProcessor implements BookFeeder {

    private static final String START_MARKER = "*** START OF THE PROJECT GUTENBERG EBOOK";
    private static final String END_MARKER = "*** END OF THE PROJECT GUTENBERG EBOOK";


    public GutenbergBookProcessor() {}

    @Override
    public void processData(RawBook rawBook, Consumer<Book> bookConsumer) {
        if (rawBookHaveNoContent(rawBook)) {
            System.err.println("RawBook inválido o vacío.");
            return;
        }
        int bookId = rawBook.bookId();
        String text = rawBook.body();
        if (!contentHaveGutenbergStructure(text)) {
            System.err.println("Marcadores de Project Gutenberg no encontrados para el libro ID: " + bookId);
            return;
        }
        try {
            String[] headerAndRest = text.split(Pattern.quote(START_MARKER), 2);
            String header = obtainHeader(headerAndRest);
            String body = obtainBody(headerAndRest);
            Book book = new Book(bookId, header, body);
            bookConsumer.accept(book);
        } catch (Exception e) {
            System.err.println("Error procesando el libro ID " + bookId + ": " + e.getMessage());
        }
    }

    private static String obtainHeader(String[] headerAndRest) {
        return headerAndRest[0].strip();
    }

    private static String obtainBody(String[] headerAndRest) {
        String[] bodyAndFooter = headerAndRest[1].split(Pattern.quote(END_MARKER), 2);
        return bodyAndFooter[0].strip();
    }

    private static boolean contentHaveGutenbergStructure(String text) {
        return text.contains(START_MARKER) && text.contains(END_MARKER);
    }

    private static boolean rawBookHaveNoContent(RawBook rawBook) {
        return rawBook == null || rawBook.body() == null;
    }
}