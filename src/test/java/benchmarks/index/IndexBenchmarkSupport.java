package benchmarks.index;

import benchmarks.common.BenchmarkRunner;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import controller.index.InvertedIndex;
import org.bson.Document;
import org.openjdk.jmh.runner.RunnerException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import java.util.stream.Stream;

public final class IndexBenchmarkSupport {

    public static final String MONGO_URI = "mongodb://localhost:27017/?serverSelectionTimeoutMS=1000";
    public static final String MONGO_DATABASE = "index_benchmark";
    public static final String MONGO_COLLECTION = "inverted_index";

    private IndexBenchmarkSupport() {}

    public static boolean isMongoAvailable() {
        try (MongoClient client = MongoClients.create(MONGO_URI)) {
            client.getDatabase("admin").runCommand(new Document("ping", 1));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public static String[] availableStructures() {
        return Stream.of(IndexStructure.values())
                .filter(structure -> structure != IndexStructure.MONGO || isMongoAvailable())
                .map(Enum::name)
                .toArray(String[]::new);
    }

    public static void resetMongo(IndexStructure structure) {
        if (structure != IndexStructure.MONGO) return;
        if (!isMongoAvailable()) throw new IllegalStateException("MongoDB no está arrancado en localhost:27017");
        dropMongoDatabase();
    }

    public static void dispose(IndexStructure structure, InvertedIndex index, Path workDir) throws Exception {
        if (index instanceof AutoCloseable closeable) closeable.close();
        deleteRecursively(workDir);
        if (structure == IndexStructure.MONGO) dropMongoDatabase();
    }

    public static void deleteRecursively(Path root) throws IOException {
        if (root == null || !Files.exists(root)) return;
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList())
                Files.delete(path);
        }
    }

    public static void run(Class<?> benchmark) throws RunnerException {
        String[] structures = availableStructures();
        if (structures.length < IndexStructure.values().length)
            System.out.println("[BENCHMARK] MongoDB no está arrancado: se omite la estructura MONGO.");
        BenchmarkRunner.run(benchmark, Map.of("structure", structures));
    }

    private static void dropMongoDatabase() {
        try (MongoClient client = MongoClients.create(MONGO_URI)) {
            client.getDatabase(MONGO_DATABASE).drop();
        }
    }
}
