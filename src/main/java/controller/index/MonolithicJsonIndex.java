package controller.index;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;

public class MonolithicJsonIndex implements InvertedIndex {

    private static final Type INDEX_TYPE = new TypeToken<TreeMap<String, TreeSet<Integer>>>() {}.getType();
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private final Path indexFile;
    private final Tokenizer tokenizer;
    private final SortedMap<String, SortedSet<Integer>> postings;

    public MonolithicJsonIndex(Path indexFile, Tokenizer tokenizer) {
        this.indexFile = indexFile;
        this.tokenizer = tokenizer;
        this.postings = loadExistingIndex(indexFile);
    }

    @Override
    public synchronized void indexBook(int bookId, String body) {
        Set<String> terms = new HashSet<>(tokenizer.tokenize(body));
        for (String term : terms)
            postings.computeIfAbsent(term, t -> new TreeSet<>()).add(bookId);
    }

    @Override
    public synchronized Set<Integer> search(String term) {
        List<String> tokens = tokenizer.tokenize(term);
        if (tokens.size() != 1) return Set.of();
        SortedSet<Integer> books = postings.get(tokens.getFirst());
        return books == null ? Set.of() : Collections.unmodifiableSortedSet(new TreeSet<>(books));
    }

    @Override
    public synchronized void flush() throws IOException {
        Path parent = indexFile.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        Path tmp = indexFile.resolveSibling(indexFile.getFileName() + ".tmp");
        try (Writer writer = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
            GSON.toJson(postings, writer);
        }
        Files.move(tmp, indexFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private static SortedMap<String, SortedSet<Integer>> loadExistingIndex(Path indexFile) {
        if (!Files.exists(indexFile)) return new TreeMap<>();
        try (Reader reader = Files.newBufferedReader(indexFile, StandardCharsets.UTF_8)) {
            TreeMap<String, TreeSet<Integer>> loaded = GSON.fromJson(reader, INDEX_TYPE);
            return loaded == null ? new TreeMap<>() : new TreeMap<>(loaded);
        } catch (IOException e) {
            throw new UncheckedIOException("Error reading index " + indexFile, e);
        }
    }
}
