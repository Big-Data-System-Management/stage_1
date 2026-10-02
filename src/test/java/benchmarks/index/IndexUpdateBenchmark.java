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
@Warmup(iterations = 0)
@Measurement(iterations = 3)
public class IndexUpdateBenchmark {

    private static final int NEW_BOOKS = 10;

    @Param({"JSON", "FOLDER", "MONGO"})
    private IndexStructure structure;

    @Param({"25", "50", "100"})
    private int books;

    private final Tokenizer tokenizer = new Tokenizer();
    private List<Book> existingBooks;
    private List<Book> newBooks;
    private Path workDir;
    private InvertedIndex index;

    @Setup(Level.Trial)
    public void loadBooks() throws IOException, InterruptedException {
        List<Book> corpus = BenchmarkBooks.load(books + NEW_BOOKS);
        existingBooks = corpus.subList(0, books);
        newBooks = corpus.subList(books, books + NEW_BOOKS);
    }

    @Setup(Level.Iteration)
    public void buildExistingIndex() throws IOException {
        workDir = Files.createTempDirectory("index_update_");
        IndexBenchmarkSupport.resetMongo(structure);
        index = structure.create(workDir, tokenizer);
        for (Book book : existingBooks)
            index.indexBook(book.id(), book.body());
        index.flush();
    }

    @Benchmark
    public void addNewBooks() throws IOException {
        for (Book book : newBooks)
            index.indexBook(book.id(), book.body());
        index.flush();
    }

    @TearDown(Level.Iteration)
    public void deleteIndex() throws Exception {
        IndexBenchmarkSupport.dispose(structure, index, workDir);
    }

    public static void main(String[] args) throws RunnerException {
        IndexBenchmarkSupport.run(IndexUpdateBenchmark.class);
    }
}
