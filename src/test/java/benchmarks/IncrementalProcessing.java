package benchmarks;

import controller.store.DatalakeLocalStoreBookHierarchy;
import controller.store.DatalakeLocalStoreIdRangeHierarchy;
import controller.store.DatalakeLocalStoreTimeHierarchy;
import controller.store.Store;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.TimeUnit;

@BenchmarkMode({Mode.AverageTime, Mode.Throughput})
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 2, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 3, timeUnit = TimeUnit.SECONDS)
@Fork(1)
public class IncrementalProcessing {

    @Param({"TIME_HIERARCHY", "BOOK_HIERARCHY", "ID_RANGE_HIERARCHY"})
    private String storeStrategy;

    @Param({"15"})
    private int existingBookId;

    @Param({"999999"})
    private int nonExistingBookId;

    private Store activeStore;

    @Setup(Level.Trial)
    public void setupBenchmark() {
        this.activeStore = createStoreInstance();
        System.out.printf("%n[SETUP] Evaluando índices sobre DataLake REAL (%s)...%n", storeStrategy);
    }

    private Store createStoreInstance() {
        String targetPath = BenchmarkPaths.getPathForStrategy(storeStrategy);
        return switch (storeStrategy) {
            case "TIME_HIERARCHY" -> new DatalakeLocalStoreTimeHierarchy(targetPath);
            case "BOOK_HIERARCHY" -> new DatalakeLocalStoreBookHierarchy(targetPath);
            case "ID_RANGE_HIERARCHY" -> new DatalakeLocalStoreIdRangeHierarchy(targetPath);
            default -> throw new IllegalArgumentException("Estrategia no reconocida: " + storeStrategy);
        };
    }

    @Benchmark
    public void measureColdIndexingOverhead(Blackhole bh) {
        Store store = createStoreInstance();
        bh.consume(store.exists(existingBookId));
    }

    @Benchmark
    @OutputTimeUnit(TimeUnit.NANOSECONDS)
    public void measureExistsHit(Blackhole bh) {
        boolean exists = activeStore.exists(existingBookId);
        bh.consume(exists);
    }

    @Benchmark
    @OutputTimeUnit(TimeUnit.NANOSECONDS)
    public void measureExistsMiss(Blackhole bh) {
        boolean exists = activeStore.exists(nonExistingBookId);
        bh.consume(exists);
    }
}