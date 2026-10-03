package benchmarks.datalake;

import benchmarks.common.BenchmarkRunner;
import controller.feeder.BookFeeder;
import controller.feeder.gutenberg.GutenbergBookDownloader;
import controller.feeder.gutenberg.GutenbergBookProcessor;
import controller.store.DatalakeLocalStoreBookHierarchy;
import controller.store.DatalakeLocalStoreIdRangeHierarchy;
import controller.store.DatalakeLocalStoreTimeHierarchy;
import controller.store.Store;
import model.Book;
import model.RawBook;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;
import org.openjdk.jmh.runner.RunnerException;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

@State(Scope.Benchmark)
@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Fork(value = 1)
@Warmup(iterations = 2)
@Measurement(iterations = 5)
public class BookDataProcessBenchmark {

    private static final int TOTAL_BOOKS = 100;
    private static final int MAX_BOOK_ID_TO_TRY = 300;
    private static final long DOWNLOAD_DELAY_MS = 1000;
    private static final Path RAW_BOOKS_DIR = Path.of("benchmark", "raw");

    @Param({"TIME_HIERARCHY", "BOOK_HIERARCHY", "ID_RANGE_HIERARCHY"})
    private String storeStrategy;

    private final BookFeeder feeder = new GutenbergBookProcessor();
    private final List<RawBook> rawBooks = new ArrayList<>();
    private final List<Book> parsedBooks = new ArrayList<>();

    private Path iterationDatalake;
    private Store activeStore;
    private int storedBooks;

    @Setup(Level.Trial)
    public void loadBooks() throws IOException, InterruptedException {
        Files.createDirectories(RAW_BOOKS_DIR);
        for (int id = 1; id <= MAX_BOOK_ID_TO_TRY && rawBooks.size() < TOTAL_BOOKS; id++) {
            Optional<RawBook> rawBook = loadRawBook(id);
            if (rawBook.isEmpty()) continue;
            List<Book> parsed = parse(rawBook.get());
            if (parsed.isEmpty()) continue;
            rawBooks.add(rawBook.get());
            parsedBooks.addAll(parsed);
        }
        if (rawBooks.size() < TOTAL_BOOKS)
            throw new IllegalStateException("Solo hay " + rawBooks.size() + " libros válidos, se necesitan " + TOTAL_BOOKS);
    }

    @Setup(Level.Iteration)
    public void createEmptyDatalake() throws IOException {
        iterationDatalake = Files.createTempDirectory("datalake_bench_" + storeStrategy.toLowerCase(Locale.ROOT) + "_");
        activeStore = createStore(iterationDatalake.toString());
        storedBooks = 0;
    }

    @TearDown(Level.Iteration)
    public void checkAndDeleteDatalake() throws IOException {
        try {
            if (storedBooks != TOTAL_BOOKS)
                throw new IllegalStateException("Se guardaron " + storedBooks + " libros de " + TOTAL_BOOKS);
        } finally {
            deleteRecursively(iterationDatalake);
        }
    }

    @Benchmark
    @OperationsPerInvocation(TOTAL_BOOKS)
    public void measureSplitAndStore(Blackhole blackhole) {
        for (RawBook rawBook : rawBooks)
            feeder.processData(rawBook, book -> store(book, blackhole));
    }

    @Benchmark
    @OperationsPerInvocation(TOTAL_BOOKS)
    public void measureStoreOnly(Blackhole blackhole) {
        for (Book book : parsedBooks)
            store(book, blackhole);
    }

    private void store(Book book, Blackhole blackhole) {
        try {
            activeStore.storeData(book);
            storedBooks++;
            blackhole.consume(book.id());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Optional<RawBook> loadRawBook(int id) throws IOException, InterruptedException {
        Path cachedFile = RAW_BOOKS_DIR.resolve("pg" + id + ".txt");
        if (Files.exists(cachedFile))
            return Optional.of(new RawBook(id, Files.readString(cachedFile, StandardCharsets.UTF_8)));
        Thread.sleep(DOWNLOAD_DELAY_MS);
        String text;
        try {
            text = GutenbergBookDownloader.downloadBook(id);
        } catch (IOException e) {
            return Optional.empty();
        }
        if (text == null || text.isEmpty()) return Optional.empty();
        Files.writeString(cachedFile, text, StandardCharsets.UTF_8);
        return Optional.of(new RawBook(id, text));
    }

    private List<Book> parse(RawBook rawBook) {
        List<Book> books = new ArrayList<>();
        feeder.processData(rawBook, books::add);
        return books;
    }

    private Store createStore(String path) {
        return switch (storeStrategy) {
            case "TIME_HIERARCHY" -> new DatalakeLocalStoreTimeHierarchy(path);
            case "BOOK_HIERARCHY" -> new DatalakeLocalStoreBookHierarchy(path);
            case "ID_RANGE_HIERARCHY" -> new DatalakeLocalStoreIdRangeHierarchy(path);
            default -> throw new IllegalArgumentException("Estrategia no reconocida: " + storeStrategy);
        };
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (root == null || !Files.exists(root)) return;
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList())
                Files.delete(path);
        }
    }

    public static void main(String[] args) throws RunnerException {
        BenchmarkRunner.run(BookDataProcessBenchmark.class, args);
    }
}