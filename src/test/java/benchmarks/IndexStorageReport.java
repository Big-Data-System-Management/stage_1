package benchmarks;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import controller.index.InvertedIndex;
import controller.index.Tokenizer;
import model.Book;
import org.bson.Document;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

public class IndexStorageReport {

    private static final int[] BOOK_COUNTS = {25, 50, 100};
    private static final long BLOCK_SIZE = 4096;
    private static final String HEADER = "structure,books,terms,build_ms,files,directories,content_bytes,disk_bytes,retained_heap_bytes";

    record Row(IndexStructure structure, int books, int terms, long buildMillis,
               long files, long directories, long contentBytes, long diskBytes, long retainedHeapBytes) {

        String toCsv() {
            return String.join(",", structure.name(), String.valueOf(books), String.valueOf(terms),
                    String.valueOf(buildMillis), String.valueOf(files), String.valueOf(directories),
                    String.valueOf(contentBytes), String.valueOf(diskBytes), String.valueOf(retainedHeapBytes));
        }
    }

    record DiskUsage(long files, long directories, long contentBytes, long diskBytes) {}

    public static void main(String[] args) throws Exception {
        Tokenizer tokenizer = new Tokenizer();
        List<IndexStructure> structures = Stream.of(IndexBenchmarkSupport.availableStructures())
                .map(IndexStructure::valueOf).toList();
        if (structures.size() < IndexStructure.values().length)
            System.out.println("[BENCHMARK] MongoDB no está arrancado: se omite la estructura MONGO.");

        List<Book> allBooks = BenchmarkBooks.load(BOOK_COUNTS[BOOK_COUNTS.length - 1]);
        List<Row> rows = new ArrayList<>();
        for (int count : BOOK_COUNTS) {
            List<Book> corpus = allBooks.subList(0, count);
            int terms = vocabularySize(corpus, tokenizer);
            for (IndexStructure structure : structures) {
                Row row = measure(structure, corpus, terms, tokenizer);
                rows.add(row);
                print(row);
            }
        }
        write(rows);
    }

    private static Row measure(IndexStructure structure, List<Book> corpus, int terms, Tokenizer tokenizer) throws Exception {
        Path workDir = Files.createTempDirectory("index_storage_");
        IndexBenchmarkSupport.resetMongo(structure);
        InvertedIndex index = null;
        try {
            long heapBefore = usedHeapAfterGc();
            long start = System.nanoTime();
            index = structure.create(workDir, tokenizer);
            for (Book book : corpus)
                index.indexBook(book.id(), book.body());
            index.flush();
            long buildMillis = (System.nanoTime() - start) / 1_000_000;
            long retainedHeap = Math.max(0, usedHeapAfterGc() - heapBefore);
            index.search("whale");
            DiskUsage disk = structure == IndexStructure.MONGO ? mongoDiskUsage() : fileDiskUsage(workDir);
            return new Row(structure, corpus.size(), terms, buildMillis,
                    disk.files(), disk.directories(), disk.contentBytes(), disk.diskBytes(), retainedHeap);
        } finally {
            IndexBenchmarkSupport.dispose(structure, index, workDir);
        }
    }

    private static int vocabularySize(List<Book> corpus, Tokenizer tokenizer) {
        Set<String> vocabulary = new HashSet<>();
        for (Book book : corpus)
            vocabulary.addAll(tokenizer.tokenize(book.body()));
        return vocabulary.size();
    }

    private static long usedHeapAfterGc() throws InterruptedException {
        MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
        for (int i = 0; i < 3; i++) {
            System.gc();
            Thread.sleep(100);
        }
        return memory.getHeapMemoryUsage().getUsed();
    }

    private static DiskUsage fileDiskUsage(Path root) throws IOException {
        long files = 0, directories = 0, contentBytes = 0, diskBytes = 0;
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.toList()) {
                if (path.equals(root)) continue;
                if (Files.isDirectory(path)) {
                    directories++;
                    continue;
                }
                long size = Files.size(path);
                files++;
                contentBytes += size;
                diskBytes += Math.max(1, (size + BLOCK_SIZE - 1) / BLOCK_SIZE) * BLOCK_SIZE;
            }
        }
        return new DiskUsage(files, directories, contentBytes, diskBytes);
    }

    private static DiskUsage mongoDiskUsage() {
        try (MongoClient client = MongoClients.create(IndexBenchmarkSupport.MONGO_URI)) {
            Document stats = client.getDatabase(IndexBenchmarkSupport.MONGO_DATABASE)
                    .getCollection(IndexBenchmarkSupport.MONGO_COLLECTION)
                    .aggregate(List.of(new Document("$collStats", new Document("storageStats", new Document()))))
                    .first();
            Document storage = stats.get("storageStats", Document.class);
            long documents = storage.get("count", Number.class).longValue();
            long contentBytes = storage.get("size", Number.class).longValue();
            long diskBytes = storage.get("storageSize", Number.class).longValue()
                    + storage.get("totalIndexSize", Number.class).longValue();
            return new DiskUsage(documents, 0, contentBytes, diskBytes);
        }
    }

    private static void print(Row row) {
        System.out.printf(Locale.ROOT, "[BENCHMARK] %-6s %3d libros | %,7d términos | %,8d ms | %,7d ficheros | %,8.2f MB contenido | %,8.2f MB en disco | %,8.2f MB heap%n",
                row.structure(), row.books(), row.terms(), row.buildMillis(), row.files(),
                row.contentBytes() / 1e6, row.diskBytes() / 1e6, row.retainedHeapBytes() / 1e6);
    }

    private static void write(List<Row> rows) throws IOException {
        BenchmarkRunner.writeCsv(IndexStorageReport.class, HEADER, rows.stream().map(Row::toCsv).toList());
    }
}
