package benchmarks.metadata;

import benchmarks.common.BenchmarkFiles;
import benchmarks.common.BenchmarkRunner;
import controller.datamart.SqliteMetadataRepository;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;
import org.openjdk.jmh.runner.RunnerException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;

@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Fork(1)
@Warmup(iterations = 2, time = 2)
@Measurement(iterations = 5, time = 2)
public class MetadataQueryBenchmark {

    private static final int QUERIES = 100;
    private static final long QUERY_SEED = 7;

    @Param({"500", "5000", "50000"})
    private int books;

    private Path databaseDir;
    private SqliteMetadataRepository repository;
    private List<Integer> bookIds;
    private List<String> titles;
    private List<String> authors;

    @Setup(Level.Trial)
    public void createDatabaseAndQueries() throws Exception {
        databaseDir = Files.createTempDirectory("metadata_query_");
        repository = new SqliteMetadataRepository(databaseDir.resolve("metadata.db"));
        repository.saveAll(SyntheticMetadata.generate(books));

        Random random = new Random(QUERY_SEED);
        bookIds = new ArrayList<>();
        titles = new ArrayList<>();
        authors = new ArrayList<>();
        for (int i = 0; i < QUERIES; i++) {
            int bookId = 1 + random.nextInt(books);
            bookIds.add(bookId);
            titles.add(SyntheticMetadata.title(bookId));
            authors.add(SyntheticMetadata.author(random.nextInt(SyntheticMetadata.authorCount(books))));
        }
    }

    @Benchmark
    @OperationsPerInvocation(QUERIES)
    public void findPathById(Blackhole blackhole) {
        for (int bookId : bookIds)
            blackhole.consume(repository.findById(bookId).map(book -> book.bodyPath()));
    }

    @Benchmark
    @OperationsPerInvocation(QUERIES)
    public void findPathByTitle(Blackhole blackhole) {
        for (String title : titles)
            blackhole.consume(repository.findByTitle(title));
    }

    @Benchmark
    @OperationsPerInvocation(QUERIES)
    public void findBooksByAuthor(Blackhole blackhole) {
        for (String author : authors)
            blackhole.consume(repository.findByAuthor(author));
    }

    @TearDown(Level.Trial)
    public void deleteDatabase() throws Exception {
        repository.close();
        BenchmarkFiles.deleteRecursively(databaseDir);
    }

    public static void main(String[] args) throws RunnerException {
        BenchmarkRunner.run(MetadataQueryBenchmark.class, args);
    }
}
