package benchmarks.metadata;

import benchmarks.common.BenchmarkFiles;
import benchmarks.common.BenchmarkRunner;
import controller.datamart.SqliteMetadataRepository;
import model.BookMetadata;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.runner.RunnerException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

@State(Scope.Benchmark)
@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Fork(1)
@Warmup(iterations = 1)
@Measurement(iterations = 3)
public class MetadataInsertBenchmark {

    @Param({"500", "5000", "50000"})
    private int books;

    private List<BookMetadata> metadata;
    private Path databaseDir;
    private SqliteMetadataRepository repository;

    @Setup(Level.Trial)
    public void generateMetadata() {
        metadata = SyntheticMetadata.generate(books);
    }

    @Setup(Level.Iteration)
    public void createEmptyDatabase() throws Exception {
        databaseDir = Files.createTempDirectory("metadata_insert_");
        repository = new SqliteMetadataRepository(databaseDir.resolve("metadata.db"));
    }

    @Benchmark
    public void insertAllInOneTransaction() {
        repository.saveAll(metadata);
    }

    @Benchmark
    public void insertOneTransactionPerBook() {
        for (BookMetadata book : metadata)
            repository.save(book);
    }

    @TearDown(Level.Iteration)
    public void deleteDatabase() throws Exception {
        if (repository.count() != books)
            throw new IllegalStateException("Se insertaron " + repository.count() + " libros de " + books);
        repository.close();
        BenchmarkFiles.deleteRecursively(databaseDir);
    }

    public static void main(String[] args) throws RunnerException {
        BenchmarkRunner.run(MetadataInsertBenchmark.class, args);
    }
}
