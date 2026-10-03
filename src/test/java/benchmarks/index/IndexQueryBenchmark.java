package benchmarks.index;

import benchmarks.common.BenchmarkBooks;
import controller.index.InvertedIndex;
import controller.index.Tokenizer;
import model.Book;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;
import org.openjdk.jmh.runner.RunnerException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Fork(1)
@Warmup(iterations = 2, time = 2)
@Measurement(iterations = 5, time = 2)
public class IndexQueryBenchmark {

    private static final int FREQUENT_TERMS = 40;
    private static final int RANDOM_TERMS = 40;
    private static final int MISSING_TERMS = 20;
    private static final int QUERIES = FREQUENT_TERMS + RANDOM_TERMS + MISSING_TERMS;

    @Param({"JSON", "FOLDER", "MONGO"})
    private IndexStructure structure;

    @Param({"25", "50", "100"})
    private int books;

    private final Tokenizer tokenizer = new Tokenizer();
    private List<String> queries;
    private Path workDir;
    private InvertedIndex index;

    @Setup(Level.Trial)
    public void buildIndexAndQueries() throws IOException, InterruptedException {
        List<Book> corpus = BenchmarkBooks.load(books);
        workDir = Files.createTempDirectory("index_query_");
        IndexBenchmarkSupport.resetMongo(structure);
        index = structure.create(workDir, tokenizer);
        for (Book book : corpus)
            index.indexBook(book.id(), book.body());
        index.flush();
        queries = buildQueries(corpus);
    }

    @Benchmark
    @OperationsPerInvocation(QUERIES)
    public void searchTerms(Blackhole blackhole) {
        for (String query : queries)
            blackhole.consume(index.search(query));
    }

    @TearDown(Level.Trial)
    public void deleteIndex() throws Exception {
        IndexBenchmarkSupport.dispose(structure, index, workDir);
    }

    private List<String> buildQueries(List<Book> corpus) {
        Map<String, Integer> documentFrequency = new HashMap<>();
        for (Book book : corpus)
            for (String term : new HashSet<>(tokenizer.tokenize(book.body())))
                documentFrequency.merge(term, 1, Integer::sum);

        List<String> byFrequency = new ArrayList<>(documentFrequency.keySet());
        byFrequency.sort(Comparator.comparing((String term) -> -documentFrequency.get(term)).thenComparing(term -> term));

        List<String> frequent = byFrequency.subList(0, FREQUENT_TERMS);
        List<String> others = new ArrayList<>(byFrequency.subList(FREQUENT_TERMS, byFrequency.size()));
        Collections.shuffle(others, new Random(42));

        List<String> result = new ArrayList<>(frequent);
        result.addAll(others.subList(0, RANDOM_TERMS));
        IntStream.range(0, MISSING_TERMS).forEach(i -> result.add("missingterm" + i));
        return List.copyOf(result);
    }

    public static void main(String[] args) throws RunnerException {
        IndexBenchmarkSupport.run(IndexQueryBenchmark.class, args);
    }
}
