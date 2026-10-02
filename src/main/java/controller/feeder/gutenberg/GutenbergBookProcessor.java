package controller.feeder.gutenberg;

import controller.feeder.BookFeeder;
import model.Book;
import model.RawBook;

import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class GutenbergBookProcessor implements BookFeeder {

    private static final Pattern START_MARKER = Pattern.compile(
            "\\*\\*\\* ?START OF (THE|THIS) PROJECT GUTENBERG E-?BOOK[^\\r\\n]*", Pattern.CASE_INSENSITIVE);
    private static final Pattern END_MARKER = Pattern.compile(
            "\\*\\*\\* ?END OF (THE|THIS) PROJECT GUTENBERG E-?BOOK", Pattern.CASE_INSENSITIVE);

    public GutenbergBookProcessor() {}

    @Override
    public void processData(RawBook rawBook, Consumer<Book> bookConsumer) {
        if (rawBookHaveNoContent(rawBook)) {
            System.err.println("RawBook inválido o vacío.");
            return;
        }
        int bookId = rawBook.bookId();
        String text = rawBook.body();
        Matcher start = START_MARKER.matcher(text);
        Matcher end = END_MARKER.matcher(text);
        if (!start.find() || !end.find(start.end())) {
            System.err.println("Marcadores de Project Gutenberg no encontrados para el libro ID: " + bookId);
            return;
        }
        String header = text.substring(0, start.start()).strip();
        String body = text.substring(start.end(), end.start()).strip();
        bookConsumer.accept(new Book(bookId, header, body));
    }

    private static boolean rawBookHaveNoContent(RawBook rawBook) {
        return rawBook == null || rawBook.body() == null;
    }
}
