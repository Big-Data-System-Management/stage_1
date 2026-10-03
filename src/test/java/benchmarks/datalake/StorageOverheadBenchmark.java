package benchmarks.datalake;

import benchmarks.common.BenchmarkRunner;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;

public class StorageOverheadBenchmark {

    public record StorageMetrics(
            long totalFiles,
            long totalDirectories,
            long totalSizeBytes,
            long averageFileSize
    ) {
        @Override
        public String toString() {
            return String.format("""
                === STORAGE METRICS ===
                Total files          : %d
                Total directories    : %d
                Size on disk (MB)    : %.2f MB
                Average file size    : %d bytes
                """,
                    totalFiles,
                    totalDirectories,
                    totalSizeBytes / (1024.0 * 1024.0),
                    averageFileSize
            );
        }
    }

    public static StorageMetrics analyze(String dataLakePath) throws IOException {
        Path root = Paths.get(dataLakePath);
        if (!Files.exists(root)) {
            throw new IllegalArgumentException("La ruta especificada no existe: " + dataLakePath);
        }

        MetricsVisitor visitor = new MetricsVisitor();
        Files.walkFileTree(root, visitor);
        long totalSubdirectories = Math.max(0, visitor.directoryCount - 1);
        long avgSize = visitor.fileCount > 0 ? visitor.totalSize / visitor.fileCount : 0;

        return new StorageMetrics(visitor.fileCount, totalSubdirectories, visitor.totalSize, avgSize);
    }

    private static class MetricsVisitor extends SimpleFileVisitor<Path> {
        private long fileCount = 0;
        private long directoryCount = 0;
        private long totalSize = 0;

        @Override
        public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
            directoryCount++;
            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
            fileCount++;
            totalSize += attrs.size();
            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException {
            return FileVisitResult.CONTINUE;
        }
    }

    public static void main(String[] args) throws IOException {
        String[] strategies = {"TIME_HIERARCHY", "BOOK_HIERARCHY", "ID_RANGE_HIERARCHY"};
        List<String> rows = new ArrayList<>();

        for (String strategy : strategies) {
            String path = BenchmarkPaths.getPathForStrategy(strategy);
            System.out.println("Strategy: " + strategy + " -> Path: " + path);
            try {
                if (Files.exists(Paths.get(path))) {
                    StorageMetrics metrics = analyze(path);
                    System.out.println(metrics);
                    rows.add(String.join(",", strategy, String.valueOf(metrics.totalFiles()),
                            String.valueOf(metrics.totalDirectories()), String.valueOf(metrics.totalSizeBytes()),
                            String.valueOf(metrics.averageFileSize())));
                } else {
                    System.out.println("Path not found, skipping.\n");
                }
            } catch (IOException e) {
                System.err.println("Error analyzing storage for: " + strategy);
                e.printStackTrace();
            }
        }

        BenchmarkRunner.writeCsv(StorageOverheadBenchmark.class,
                "strategy,files,directories,size_bytes,average_file_size_bytes", rows);
    }
}