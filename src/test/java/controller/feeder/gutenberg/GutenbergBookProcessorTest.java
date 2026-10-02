package controller.feeder.gutenberg;

import model.Book;
import model.RawBook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GutenbergBookProcessorTest {

    private final GutenbergBookProcessor processor = new GutenbergBookProcessor();

    @ParameterizedTest
    @ValueSource(strings = {
            "*** START OF THE PROJECT GUTENBERG EBOOK PI ***",
            "*** START OF THIS PROJECT GUTENBERG EBOOK PI ***",
            "***START OF THE PROJECT GUTENBERG EBOOK PI***",
            "*** Start of the Project Gutenberg eBook Pi ***",
            "*** START OF THIS PROJECT GUTENBERG E-BOOK PI ***"})
    void acceptsTheStartMarkerVariants(String startMarker) {
        String text = "Title: Pi\r\n\r\n" + startMarker + "\r\n\r\n3.14159\r\n\r\n*** END OF THIS PROJECT GUTENBERG EBOOK PI ***\r\nfooter";

        Book book = process(text);

        assertEquals("Title: Pi", book.head());
        assertEquals("3.14159", book.body());
    }

    @Test
    void bodyDoesNotContainTheRestOfTheStartMarkerLine() {
        String text = "Title: Alice\n*** START OF THE PROJECT GUTENBERG EBOOK ALICE'S ADVENTURES IN WONDERLAND ***\n"
                + "Alice was beginning to get very tired\n"
                + "*** END OF THE PROJECT GUTENBERG EBOOK ALICE'S ADVENTURES IN WONDERLAND ***\n";

        assertEquals("Alice was beginning to get very tired", process(text).body());
    }

    @Test
    void ignoresAnEndMarkerBeforeTheStartMarker() {
        String text = "*** END OF THE PROJECT GUTENBERG EBOOK X ***\nheader\n"
                + "*** START OF THE PROJECT GUTENBERG EBOOK X ***\nbody\n"
                + "*** END OF THE PROJECT GUTENBERG EBOOK X ***\n";

        assertEquals("body", process(text).body());
    }

    @Test
    void skipsBooksWithoutMarkers() {
        List<Book> books = new ArrayList<>();
        processor.processData(new RawBook(1, "plain text without markers"), books::add);

        assertTrue(books.isEmpty());
    }

    private Book process(String text) {
        List<Book> books = new ArrayList<>();
        processor.processData(new RawBook(1, text), books::add);
        assertEquals(1, books.size());
        return books.getFirst();
    }
}
