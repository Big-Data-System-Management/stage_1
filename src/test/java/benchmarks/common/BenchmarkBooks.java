package benchmarks.common;

import controller.feeder.BookFeeder;
import controller.feeder.gutenberg.GutenbergBookDownloader;
import controller.feeder.gutenberg.GutenbergBookProcessor;
import model.Book;
import model.RawBook;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class BenchmarkBooks {

    private static final Path RAW_BOOKS_DIR = Path.of("benchmark", "raw");
    private static final int MAX_BOOK_ID_TO_TRY = 1000;
    private static final long DOWNLOAD_DELAY_MS = 1000;

    private BenchmarkBooks() {}

    public static List<Book> load(int count) throws IOException, InterruptedException {
        Files.createDirectories(RAW_BOOKS_DIR);
        BookFeeder feeder = new GutenbergBookProcessor();
        List<Book> books = new ArrayList<>();
        for (int id = 1; id <= MAX_BOOK_ID_TO_TRY && books.size() < count; id++) {
            int bookId = id;
            rawText(bookId).ifPresent(text -> feeder.processData(new RawBook(bookId, text), books::add));
        }
        if (books.size() < count)
            throw new IllegalStateException("Solo hay " + books.size() + " libros válidos, se necesitan " + count);
        return List.copyOf(books);
    }

    private static Optional<String> rawText(int id) throws IOException, InterruptedException {
        Path cached = RAW_BOOKS_DIR.resolve("pg" + id + ".txt");
        Path missing = RAW_BOOKS_DIR.resolve("pg" + id + ".missing");
        if (Files.exists(cached)) return Optional.of(Files.readString(cached, StandardCharsets.UTF_8));
        if (Files.exists(missing)) return Optional.empty();
        Thread.sleep(DOWNLOAD_DELAY_MS);
        String text;
        try {
            text = GutenbergBookDownloader.downloadBook(id);
        } catch (IOException e) {
            if (String.valueOf(e.getMessage()).contains("404")) Files.createFile(missing);
            return Optional.empty();
        }
        if (text == null || text.isEmpty()) return Optional.empty();
        Files.writeString(cached, text, StandardCharsets.UTF_8);
        return Optional.of(text);
    }
}
