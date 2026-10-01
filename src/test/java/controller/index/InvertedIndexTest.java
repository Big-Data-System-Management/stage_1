package controller.index;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class InvertedIndexTest {

    private static final String MONGO_URI = "mongodb://localhost:27017/?serverSelectionTimeoutMS=500";
    private static final boolean MONGO_AVAILABLE = isMongoAvailable();

    private static final Map<Integer, String> BOOKS = Map.of(
            5, "An adventure on an island. Shipwreck!",
            12, "Adventure and shipwreck.",
            17, "A shipwreck, a storm and a Café.",
            1342, "The island, the ISLAND and the storm.");

    private static final List<String> VOCABULARY = List.of("adventure", "island", "shipwreck", "storm", "cafe");

    @TempDir
    Path tempDir;

    private final Tokenizer tokenizer = new Tokenizer();
    private final List<AutoCloseable> openIndexes = new ArrayList<>();
    private final Set<String> mongoDatabases = new TreeSet<>();

    enum Structure { JSON, FOLDER, MONGO }

    @AfterEach
    void cleanUp() throws Exception {
        for (AutoCloseable index : openIndexes) index.close();
        if (mongoDatabases.isEmpty()) return;
        try (MongoClient client = MongoClients.create(MONGO_URI)) {
            for (String database : mongoDatabases) client.getDatabase(database).drop();
        }
    }

    @ParameterizedTest
    @EnumSource(Structure.class)
    void findsTheBooksContainingATerm(Structure structure) throws Exception {
        InvertedIndex index = indexAll(open(structure, "main"));

        assertEquals(Set.of(5, 12), index.search("adventure"));
        assertEquals(Set.of(5, 1342), index.search("island"));
        assertEquals(Set.of(), index.search("dragon"));
    }

    @ParameterizedTest
    @EnumSource(Structure.class)
    void normalizesTheQueryWithTheTokenizer(Structure structure) throws Exception {
        InvertedIndex index = indexAll(open(structure, "main"));

        assertEquals(Set.of(5, 1342), index.search("ISLAND"));
        assertEquals(Set.of(17), index.search("CAFÉ"));
    }

    @ParameterizedTest
    @EnumSource(Structure.class)
    void returnsNothingForStopwordsOrSeveralWords(Structure structure) throws Exception {
        InvertedIndex index = indexAll(open(structure, "main"));

        assertEquals(Set.of(), index.search("the"));
        assertEquals(Set.of(), index.search("adventure island"));
    }

    @ParameterizedTest
    @EnumSource(Structure.class)
    void returnsBookIdsInAscendingOrder(Structure structure) throws Exception {
        InvertedIndex index = open(structure, "main");
        index.indexBook(1342, "storm");
        index.indexBook(5, "storm");
        index.flush();
        index.indexBook(17, "storm");

        assertEquals(List.of(5, 17, 1342), List.copyOf(index.search("storm")));
    }

    @ParameterizedTest
    @EnumSource(Structure.class)
    void indexingTheSameBookTwiceHasNoEffect(Structure structure) throws Exception {
        InvertedIndex index = open(structure, "main");
        index.indexBook(5, "whale");
        index.flush();
        index.indexBook(5, "whale whale");
        index.flush();

        assertEquals(List.of(5), List.copyOf(index.search("whale")));
    }

    @ParameterizedTest
    @EnumSource(Structure.class)
    void findsBooksThatAreNotFlushedYet(Structure structure) throws Exception {
        InvertedIndex index = open(structure, "main");
        index.indexBook(5, "whale");
        index.flush();
        index.indexBook(12, "whale");

        assertEquals(Set.of(5, 12), index.search("whale"));
    }

    @ParameterizedTest
    @EnumSource(Structure.class)
    void keepsTheIndexAfterReopening(Structure structure) throws Exception {
        indexAll(open(structure, "main"));

        InvertedIndex reopened = open(structure, "main");

        assertEquals(Set.of(5, 12), reopened.search("adventure"));
    }

    @ParameterizedTest
    @EnumSource(Structure.class)
    void incrementalUpdatesEqualAFullRebuild(Structure structure) throws Exception {
        InvertedIndex incremental = open(structure, "incremental");
        incremental.indexBook(5, BOOKS.get(5));
        incremental.indexBook(12, BOOKS.get(12));
        incremental.flush();
        incremental.indexBook(17, BOOKS.get(17));
        incremental.indexBook(1342, BOOKS.get(1342));
        incremental.flush();

        InvertedIndex full = indexAll(open(structure, "full"));

        for (String term : VOCABULARY)
            assertEquals(full.search(term), incremental.search(term), term);
    }

    @Test
    void allStructuresReturnTheSameResults() throws Exception {
        InvertedIndex reference = indexAll(open(Structure.JSON, "json"));
        List<InvertedIndex> others = new ArrayList<>(List.of(indexAll(open(Structure.FOLDER, "folder"))));
        if (MONGO_AVAILABLE) others.add(indexAll(open(Structure.MONGO, "mongo")));

        for (InvertedIndex other : others)
            for (String term : VOCABULARY)
                assertEquals(reference.search(term), other.search(term), term);
        assertTrue(reference.search("shipwreck").containsAll(Set.of(5, 12, 17)));
    }

    private InvertedIndex indexAll(InvertedIndex index) throws Exception {
        for (Map.Entry<Integer, String> book : BOOKS.entrySet())
            index.indexBook(book.getKey(), book.getValue());
        index.flush();
        return index;
    }

    private InvertedIndex open(Structure structure, String name) {
        return switch (structure) {
            case JSON -> new MonolithicJsonIndex(tempDir.resolve(name + ".json"), tokenizer);
            case FOLDER -> new HierarchicalFolderIndex(tempDir.resolve(name), tokenizer);
            case MONGO -> openMongo(name);
        };
    }

    private InvertedIndex openMongo(String name) {
        assumeTrue(MONGO_AVAILABLE, "MongoDB is not running on localhost:27017");
        String database = "index_test_" + tempDir.getFileName().toString().replaceAll("\\W", "") + "_" + name;
        mongoDatabases.add(database);
        MongoInvertedIndex index = new MongoInvertedIndex(MONGO_URI, database, "inverted_index", tokenizer);
        openIndexes.add(index);
        return index;
    }

    private static boolean isMongoAvailable() {
        try (MongoClient client = MongoClients.create(MONGO_URI)) {
            client.getDatabase("admin").runCommand(new Document("ping", 1));
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
