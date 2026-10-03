package controller.feeder;

import model.RawBook;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.function.IntPredicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public class LocalBookCrawler implements BookCrawler {

    private static final Pattern BOOK_FILE = Pattern.compile("pg(\\d+)\\.txt");

    private final Path directory;

    public LocalBookCrawler(Path directory) {
        this.directory = directory;
    }

    @Override
    public void crawl(int startBookId, int endBookId, IntPredicate filter, Consumer<RawBook> rawBookConsumer) {
        for (int bookId = startBookId; bookId <= endBookId; bookId++) {
            if (filter != null && !filter.test(bookId)) continue;
            Path file = directory.resolve("pg" + bookId + ".txt");
            if (!Files.exists(file)) continue;
            try {
                rawBookConsumer.accept(new RawBook(bookId, Files.readString(file, StandardCharsets.UTF_8)));
            } catch (IOException e) {
                System.err.printf("Error reading book ID %d: %s%n", bookId, e.getMessage());
            }
        }
    }

    public SortedSet<Integer> availableBookIds() throws IOException {
        SortedSet<Integer> bookIds = new TreeSet<>();
        try (Stream<Path> files = Files.list(directory)) {
            files.map(file -> BOOK_FILE.matcher(file.getFileName().toString()))
                    .filter(Matcher::matches)
                    .forEach(matcher -> bookIds.add(Integer.parseInt(matcher.group(1))));
        }
        return bookIds;
    }
}
