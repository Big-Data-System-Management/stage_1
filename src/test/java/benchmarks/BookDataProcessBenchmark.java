package benchmarks;

import controller.feeder.BookFeeder;
import controller.feeder.gutenberg.GutenbergBookProcessor;
import controller.store.DatalakeLocalStoreBookHierarchy;
import controller.store.DatalakeLocalStoreIdRangeHierarchy;
import controller.store.DatalakeLocalStoreTimeHierarchy;
import controller.store.Store;
import model.Book;
import model.RawBook;

import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

@State(Scope.Benchmark)
@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Fork(value = 1)
@Warmup(iterations = 2)
@Measurement(iterations = 5)
public class BookDataProcessBenchmark {

    private static final int TOTAL_BOOKS = 100;

    @Param({"TIME_HIERARCHY", "BOOK_HIERARCHY", "ID_RANGE_HIERARCHY"})
    private String storeStrategy;

    private Store activeStore;

    private final List<RawBook> rawDownloadedBooks = new ArrayList<>();
    private final List<Book> preParsedBooks = new ArrayList<>();
    private BookFeeder feeder;
    private Path tempBenchmarkDir;
    private long iterationStartTime;
    private final List<Double> iterationDurationsInSeconds = new ArrayList<>();

    @Setup(Level.Trial)
    public void setupBenchmark() throws IOException {
        this.tempBenchmarkDir = Files.createTempDirectory("dl_write_bench_" + storeStrategy.toLowerCase() + "_");
        this.activeStore = switch (storeStrategy) {
            case "TIME_HIERARCHY" -> new DatalakeLocalStoreTimeHierarchy(tempBenchmarkDir.toString());
            case "BOOK_HIERARCHY" -> new DatalakeLocalStoreBookHierarchy(tempBenchmarkDir.toString());
            case "ID_RANGE_HIERARCHY" -> new DatalakeLocalStoreIdRangeHierarchy(tempBenchmarkDir.toString());
            default -> throw new IllegalArgumentException("Estrategia no reconocida");
        };
    }

    @Setup(Level.Iteration)
    public void setupIteration() {
        this.iterationStartTime = System.currentTimeMillis();
    }

    @Benchmark
    @OperationsPerInvocation(TOTAL_BOOKS)
    public void measureSplitAndStoreOnly(Blackhole bh) {
        for (RawBook rawBook : rawDownloadedBooks) {
            feeder.processData(rawBook, book -> {
                try {
                    activeStore.storeData(book);
                    bh.consume(book);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        }
    }

    @Benchmark
    @OperationsPerInvocation(TOTAL_BOOKS)
    public void measureStoreOnly(Blackhole bh) throws IOException {
        for (Book book : preParsedBooks) {
            activeStore.storeData(book);
            bh.consume(book);
        }
    }

    @TearDown(Level.Trial)
    public void tearDownBenchmark() throws IOException {
        if (tempBenchmarkDir != null && Files.exists(tempBenchmarkDir)) {
            try (var stream = Files.walk(tempBenchmarkDir)) {
                stream.sorted(java.util.Comparator.reverseOrder())
                        .map(Path::toFile)
                        .forEach(java.io.File::delete);
            }
        }
    }

    public static void main(String[] args) throws RunnerException {
        Options opt = new OptionsBuilder()
                .include(BookDataProcessBenchmark.class.getSimpleName())
                .build();
        new Runner(opt).run();
    }
}