package benchmarks;

import controller.store.DatalakeLocalStoreBookHierarchy;
import controller.store.DatalakeLocalStoreIdRangeHierarchy;
import controller.store.DatalakeLocalStoreTimeHierarchy;
import controller.store.Store;
import model.Book;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;
import org.openjdk.jmh.runner.RunnerException;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.util.Comparator;
import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
@Fork(value=1)
public class RecoveryBehavior {

    @Param({"TIME_HIERARCHY", "BOOK_HIERARCHY", "ID_RANGE_HIERARCHY"})
    private String storeStrategy;

    private Path tempDatalakePath;

    @Setup(Level.Invocation)
    public void setupInconsistentDataLake() throws IOException {
        tempDatalakePath = Files.createTempDirectory("dl_recovery_" + storeStrategy.toLowerCase() + "_");

        for (int id = 1; id <= 500; id++) {
            writeCompleteBook(id);
        }
        for (int id = 501; id <= 550; id++) {
            writeIncompleteBookHeaderOnly(id);
        }
        for (int id = 551; id <= 600; id++) {
            writeAbandonedTmpFile(id);
        }
    }

    @TearDown(Level.Invocation)
    public void tearDown() throws IOException {
        deleteDirectoryRecursively(tempDatalakePath);
    }

    private Store createStoreInstance() {
        String pathStr = tempDatalakePath.toString();
        return switch (storeStrategy) {
            case "TIME_HIERARCHY" -> new DatalakeLocalStoreTimeHierarchy(pathStr);
            case "BOOK_HIERARCHY" -> new DatalakeLocalStoreBookHierarchy(pathStr);
            case "ID_RANGE_HIERARCHY" -> new DatalakeLocalStoreIdRangeHierarchy(pathStr);
            default -> throw new IllegalArgumentException("Estrategia no válida: " + storeStrategy);
        };
    }

    @Benchmark
    public void measureRecoveryTimeAfterCrash(Blackhole bh) {
        Store store = createStoreInstance();
        boolean isCorruptedBookIndexed = store.exists(505);
        if (isCorruptedBookIndexed) {
            throw new IllegalStateException("Fallo de recuperación: Se indexó un libro incompleto.");
        }
        bh.consume(store);
    }

    @Benchmark
    public void measurePipelineResumeAndRepair(Blackhole bh) throws IOException {
        Store store = createStoreInstance();
        for (int id = 501; id <= 550; id++) {
            Book repairedBook = new Book(id, "Header reparado " + id, "Body completado " + id);
            store.storeData(repairedBook);
        }
        bh.consume(store);
    }

    private void writeCompleteBook(int id) throws IOException {
        Path targetDir = resolveTargetDirectory(id);
        Files.createDirectories(targetDir);
        Files.writeString(targetDir.resolve(id + ".header.txt"), "Header " + id);
        Files.writeString(targetDir.resolve(id + ".body.txt"), "Body " + id);
    }

    private void writeIncompleteBookHeaderOnly(int id) throws IOException {
        Path targetDir = resolveTargetDirectory(id);
        Files.createDirectories(targetDir);
        Files.writeString(targetDir.resolve(id + ".header.txt"), "Header " + id);
    }

    private void writeAbandonedTmpFile(int id) throws IOException {
        Path targetDir = resolveTargetDirectory(id);
        Files.createDirectories(targetDir);
        Files.writeString(targetDir.resolve(id + ".header.txt.tmp"), "Temporal inconcluso " + id);
    }

    private Path resolveTargetDirectory(int id) {
        return switch (storeStrategy) {
            case "BOOK_HIERARCHY" -> tempDatalakePath.resolve(String.valueOf(id));
            case "ID_RANGE_HIERARCHY" -> {
                int batchNum = id / 1000;
                String batchFolder = String.format("batch_%d_to_%d", batchNum * 1000, ((batchNum + 1) * 1000) - 1);
                yield tempDatalakePath.resolve(batchFolder);
            }
            case "TIME_HIERARCHY" -> tempDatalakePath.resolve("20260929").resolve("17");
            default -> throw new IllegalArgumentException("Estrategia no válida: " + storeStrategy);
        };
    }

    private static void deleteDirectoryRecursively(Path path) throws IOException {
        if (Files.exists(path)) {
            try (var stream = Files.walk(path)) {
                stream.sorted(Comparator.reverseOrder())
                        .map(Path::toFile)
                        .forEach(File::delete);
            }
        }
    }

    public static void main(String[] args) throws RunnerException {
        BenchmarkRunner.run(RecoveryBehavior.class);
    }
}