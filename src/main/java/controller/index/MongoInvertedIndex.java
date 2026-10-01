package controller.index;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.BulkWriteOptions;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.UpdateOneModel;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.model.Updates;
import org.bson.Document;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;

import static com.mongodb.client.model.Filters.eq;

public class MongoInvertedIndex implements InvertedIndex, AutoCloseable {

    private static final String TERM_FIELD = "term";
    private static final String POSTINGS_FIELD = "postings";

    private final MongoClient client;
    private final MongoCollection<Document> collection;
    private final Tokenizer tokenizer;
    private final Map<String, SortedSet<Integer>> pendingPostings = new HashMap<>();

    public MongoInvertedIndex(String connectionUri, String databaseName, String collectionName, Tokenizer tokenizer) {
        this.client = MongoClients.create(connectionUri);
        this.collection = client.getDatabase(databaseName).getCollection(collectionName);
        this.tokenizer = tokenizer;
        this.collection.createIndex(Indexes.ascending(TERM_FIELD), new IndexOptions().unique(true));
    }

    @Override
    public synchronized void indexBook(int bookId, String body) {
        Set<String> terms = new HashSet<>(tokenizer.tokenize(body));
        for (String term : terms)
            pendingPostings.computeIfAbsent(term, t -> new TreeSet<>()).add(bookId);
    }

    @Override
    public synchronized Set<Integer> search(String term) {
        List<String> tokens = tokenizer.tokenize(term);
        if (tokens.size() != 1) return Set.of();
        String normalizedTerm = tokens.getFirst();
        SortedSet<Integer> books = readPostings(normalizedTerm);
        books.addAll(pendingPostings.getOrDefault(normalizedTerm, Collections.emptySortedSet()));
        return Collections.unmodifiableSortedSet(books);
    }

    @Override
    public synchronized void flush() {
        if (pendingPostings.isEmpty()) return;
        List<UpdateOneModel<Document>> updates = new ArrayList<>();
        for (Map.Entry<String, SortedSet<Integer>> entry : pendingPostings.entrySet())
            updates.add(toUpsert(entry.getKey(), entry.getValue()));
        collection.bulkWrite(updates, new BulkWriteOptions().ordered(false));
        pendingPostings.clear();
    }

    @Override
    public void close() {
        client.close();
    }

    private static UpdateOneModel<Document> toUpsert(String term, SortedSet<Integer> bookIds) {
        return new UpdateOneModel<>(
                eq(TERM_FIELD, term),
                Updates.addEachToSet(POSTINGS_FIELD, new ArrayList<>(bookIds)),
                new UpdateOptions().upsert(true));
    }

    private SortedSet<Integer> readPostings(String term) {
        Document document = collection.find(eq(TERM_FIELD, term)).first();
        if (document == null) return new TreeSet<>();
        return new TreeSet<>(document.getList(POSTINGS_FIELD, Integer.class));
    }
}
