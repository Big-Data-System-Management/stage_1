package benchmarks.index;

import benchmarks.common.BenchmarkBooks;
import controller.index.InvertedIndex;
import controller.index.Tokenizer;
import model.Book;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.runner.RunnerException;

import java.io.IOException;
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
public class IndexBuildBenchmark {

    @Param({"JSON", "FOLDER", "MONGO"})
    private IndexStructure structure;

    @Param({"25", "50", "100"})
    private int books;

    private final Tokenizer tokenizer = new Tokenizer();
    private List<Book> corpus;
    private Path workDir;
    private InvertedIndex index;

    @Setup(Level.Trial)
    public void loadBooks() throws IOException, InterruptedException {
        corpus = BenchmarkBooks.load(books);
    }

    @Setup(Level.Iteration)
    public void createEmptyIndex() throws IOException {
        workDir = Files.createTempDirectory("index_build_");
        IndexBenchmarkSupport.resetMongo(structure);
        index = structure.create(workDir, tokenizer);
    }

    @Benchmark
    public void buildIndexFromScratch() throws IOException {
        for (Book book : corpus)
            index.indexBook(book.id(), book.body());
        index.flush();
    }

    @TearDown(Level.Iteration)
    public void deleteIndex() throws Exception {
        IndexBenchmarkSupport.dispose(structure, index, workDir);
    }

    public static void main(String[] args) throws RunnerException {
        IndexBenchmarkSupport.run(IndexBuildBenchmark.class);
    }
}
