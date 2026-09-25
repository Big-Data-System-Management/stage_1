package benchmarks;

import controller.store.DatalakeLocalStoreBookHierarchy;
import controller.store.DatalakeLocalStoreIdRangeHierarchy;
import controller.store.DatalakeLocalStoreTimeHierarchy;
import controller.store.Store;
import model.Book;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Fork(value = 1, warmups = 1)
@Warmup(iterations = 3)
@Measurement(iterations = 5)
public class LookUpCostBenchmark {

    private static final String BASE_PATH = "/datalake"; //Esto se va a cambiar, ya que cada estructura de datalake tendrá una carpeta dedicada.
    private static final int MIN_BOOK_ID = 1;
    private static final int MAX_BOOK_ID = 10;
    @Param({"TIME_HIERARCHY", "BOOK_HIERARCHY", "ID_RANGE_HIERARCHY"})
    private String storeStrategy;

    private Store activeStore;

    @Setup(Level.Trial)
    public void setupBenchmark() {
        switch (storeStrategy) {
            case "TIME_HIERARCHY":
                this.activeStore = new DatalakeLocalStoreTimeHierarchy(BASE_PATH);
                break;
            case "BOOK_HIERARCHY":
                this.activeStore = new DatalakeLocalStoreBookHierarchy(BASE_PATH);
                break;
            case "ID_RANGE_HIERARCHY":
                this.activeStore = new DatalakeLocalStoreIdRangeHierarchy(BASE_PATH);
                break;
            default:
                throw new IllegalArgumentException("Estrategia no reconocida: " + storeStrategy);
        }
    }

    @Benchmark
    public void measureHeaderAndBodyLookup(Blackhole blackhole) {
        int targetId = ThreadLocalRandom.current().nextInt(MIN_BOOK_ID, MAX_BOOK_ID + 1);
        Book book = activeStore.getBook(targetId);
        blackhole.consume(book);
    }
}