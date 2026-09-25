package benchmarks;

import controller.Controller;
import model.OverwriteMode;
import controller.feeder.BookCrawler;
import controller.feeder.BookFeeder;
import controller.feeder.gutenberg.GutenbergBookProcessor;
import controller.feeder.gutenberg.GutenbergCrawler;
import controller.store.Store;
import controller.store.DatalakeLocalStoreTimeHierarchy;

import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;

@State(Scope.Benchmark)
@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.SECONDS)
@Fork(value = 1, warmups = 0)
@Warmup(iterations = 0)
@Measurement(iterations = 3)
public class DownloadSplitAndWriteBenchmark {

    private static final int INITIAL_ID = 1;
    private static final int FINAL_ID = 10;

    private Controller controller;
    private Path tempDatalake;
    private long iterationStartTime;
    private final List<Double> iterationDurationsInSeconds = new ArrayList<>();

    @Setup(Level.Iteration)
    public void setup() throws IOException {
        tempDatalake = Files.createTempDirectory("benchmark_datalake_");
        BookCrawler crawler = new GutenbergCrawler();
        BookFeeder feeder = new GutenbergBookProcessor();
        Store store = new DatalakeLocalStoreTimeHierarchy(tempDatalake.toString());
        this.controller = new Controller(crawler, feeder, store, OverwriteMode.OVERWRITE);
        this.iterationStartTime = System.currentTimeMillis();
    }

    @Benchmark
    @OperationsPerInvocation(FINAL_ID - INITIAL_ID + 1)
    public void measureDownloadSplitAndStore() {
        controller.downloadSplitAndWrite(INITIAL_ID, FINAL_ID);
    }

    @TearDown(Level.Iteration)
    public void tearDown() throws IOException {
        long durationMs = System.currentTimeMillis() - iterationStartTime;
        double durationSeconds = durationMs / 1000.0;
        iterationDurationsInSeconds.add(durationSeconds);

        if (tempDatalake != null && Files.exists(tempDatalake)) {
            Files.walk(tempDatalake)
                    .sorted(Comparator.reverseOrder())
                    .map(Path::toFile)
                    .forEach(File::delete);
        }
    }

    @TearDown(Level.Trial)
    public void extractConclusions() {
        if (iterationDurationsInSeconds.isEmpty()) return;

        double totalTimeSeconds = iterationDurationsInSeconds.stream()
                .mapToDouble(Double::doubleValue)
                .sum();

        int totalIterations = iterationDurationsInSeconds.size();

        int booksPerBatch = FINAL_ID - INITIAL_ID + 1;
        int totalBooksProcessed = totalIterations * booksPerBatch;

        double avgTimePerBatch = totalTimeSeconds / totalIterations;
        double avgThroughputBooksPerSec = totalBooksProcessed / totalTimeSeconds;
        double avgTimePerBook = totalTimeSeconds / totalBooksProcessed;

        System.out.println("\n==================================================");
        System.out.println("     CONCLUSIONES DEL EXPERIMENTO (THROUGHPUT)    ");
        System.out.println("==================================================");
        System.out.printf(" Rango de IDs probados:     %d a %d%n", INITIAL_ID, FINAL_ID);
        System.out.printf(" Iteraciones totales:       %d%n", totalIterations);
        System.out.printf(" Libros totales procesados: %d libros%n", totalBooksProcessed);
        System.out.printf(" Tiempo total acumulado:    %.2f segundos%n", totalTimeSeconds);
        System.out.printf(" Tiempo medio por lote:     %.2f segundos%n", avgTimePerBatch);
        System.out.println("--------------------------------------------------");
        System.out.printf(" CAUDAL (Throughput):       %.3f libros/segundo%n", avgThroughputBooksPerSec);
        System.out.printf(" LATENCIA MEDIA POR LIBRO:  %.2f segundos/libro%n", avgTimePerBook);
        System.out.println("==================================================\n");
    }

    public static void main(String[] args) throws RunnerException {
        Options opt = new OptionsBuilder()
                .include(DownloadSplitAndWriteBenchmark.class.getSimpleName())
                .build();
        new Runner(opt).run();
    }
}