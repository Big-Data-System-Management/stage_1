package benchmarks.common;

import org.openjdk.jmh.results.format.ResultFormatType;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.ChainedOptionsBuilder;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class BenchmarkRunner {

    public static final Path RESULTS_DIR = Path.of("benchmark", "results");

    private BenchmarkRunner() {}

    public static void run(Class<?> benchmark) throws RunnerException {
        run(benchmark, Map.of());
    }

    public static void run(Class<?> benchmark, Map<String, String[]> params) throws RunnerException {
        Path resultFile = resultFile(benchmark);
        createResultsDirectory();
        Locale.setDefault(Locale.ROOT);
        ChainedOptionsBuilder options = new OptionsBuilder()
                .include(benchmark.getName())
                .resultFormat(ResultFormatType.CSV)
                .result(resultFile.toString());
        params.forEach(options::param);
        new Runner(options.build()).run();
        System.out.println("[BENCHMARK] Resultados guardados en " + resultFile.toAbsolutePath());
    }

    public static Path resultFile(Class<?> benchmark) {
        return RESULTS_DIR.resolve(benchmark.getSimpleName() + ".csv");
    }

    public static void writeCsv(Class<?> benchmark, String header, List<String> rows) throws IOException {
        Path resultFile = resultFile(benchmark);
        createResultsDirectory();
        StringBuilder content = new StringBuilder(header).append('\n');
        rows.forEach(row -> content.append(row).append('\n'));
        Files.writeString(resultFile, content, StandardCharsets.UTF_8);
        System.out.println("[BENCHMARK] Resultados guardados en " + resultFile.toAbsolutePath());
    }

    private static void createResultsDirectory() {
        try {
            Files.createDirectories(RESULTS_DIR);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
