package benchmarks.metadata;

import model.BookMetadata;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public final class SyntheticMetadata {

    private static final long SEED = 42;
    private static final int BOOKS_PER_AUTHOR = 20;
    private static final List<String> LANGUAGES = List.of(
            "English", "English", "English", "English", "English", "English", "English",
            "French", "German", "Spanish");

    private SyntheticMetadata() {}

    public static List<BookMetadata> generate(int count) {
        Random random = new Random(SEED);
        int authors = authorCount(count);
        List<BookMetadata> books = new ArrayList<>(count);
        for (int id = 1; id <= count; id++) {
            String author = author(random.nextInt(authors));
            String language = LANGUAGES.get(random.nextInt(LANGUAGES.size()));
            Path bodyPath = Path.of("datalake", "20260101", String.format("%02d", id % 24), id + ".body.txt");
            books.add(new BookMetadata(id, title(id), author, language, bodyPath));
        }
        return books;
    }

    public static int authorCount(int bookCount) {
        return Math.max(1, bookCount / BOOKS_PER_AUTHOR);
    }

    public static String author(int index) {
        return "Author " + index;
    }

    public static String title(int bookId) {
        return "Title of book " + bookId;
    }
}
