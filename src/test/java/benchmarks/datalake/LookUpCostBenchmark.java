package benchmarks.datalake;

import benchmarks.common.BenchmarkRunner;
import controller.store.DatalakeLocalStoreBookHierarchy;
import controller.store.DatalakeLocalStoreIdRangeHierarchy;
import controller.store.DatalakeLocalStoreTimeHierarchy;
import controller.store.Store;
import model.Book;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;
import org.openjdk.jmh.runner.RunnerException;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Fork(value = 1, warmups = 1)
@Warmup(iterations = 3)
@Measurement(iterations = 5)
public class LookUpCostBenchmark {

    private static final int MIN_BOOK_ID = 1;
    private static final int MAX_BOOK_ID = 200;

    @Param({"TIME_HIERARCHY", "BOOK_HIERARCHY", "ID_RANGE_HIERARCHY"})
    private String storeStrategy;

    private Store activeStore;

    @Setup(Level.Trial)
    public void setupBenchmark() {
        String targetPath = BenchmarkPaths.getPathForStrategy(storeStrategy);

        this.activeStore = switch (storeStrategy) {
            case "TIME_HIERARCHY" -> new DatalakeLocalStoreTimeHierarchy(targetPath);
            case "BOOK_HIERARCHY" -> new DatalakeLocalStoreBookHierarchy(targetPath);
            case "ID_RANGE_HIERARCHY" -> new DatalakeLocalStoreIdRangeHierarchy(targetPath);
            default -> throw new IllegalArgumentException("Unknown strategy: " + storeStrategy);
        };

        System.out.printf("%n[SETUP] Using the REAL datalake at: %s%n", targetPath);
    }

    @Benchmark
    public void measureHeaderAndBodyLookup(Blackhole blackhole) {
        int targetId = ThreadLocalRandom.current().nextInt(MIN_BOOK_ID, MAX_BOOK_ID + 1);
        Book book = activeStore.getBook(targetId);
        blackhole.consume(book);
    }

    public static void main(String[] args) throws RunnerException {
        BenchmarkRunner.run(LookUpCostBenchmark.class, args);
    }
}