package controller.index;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.stream.Collectors;

public class HierarchicalFolderIndex implements InvertedIndex {

    private static final Set<String> WINDOWS_RESERVED_NAMES = Set.of(
            "con", "prn", "aux", "nul",
            "com1", "com2", "com3", "com4", "com5", "com6", "com7", "com8", "com9",
            "lpt1", "lpt2", "lpt3", "lpt4", "lpt5", "lpt6", "lpt7", "lpt8", "lpt9");

    private final Path rootDirectory;
    private final Tokenizer tokenizer;
    private final Map<String, SortedSet<Integer>> pendingPostings = new HashMap<>();

    public HierarchicalFolderIndex(Path rootDirectory, Tokenizer tokenizer) {
        this.rootDirectory = rootDirectory;
        this.tokenizer = tokenizer;
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
        SortedSet<Integer> books = readPostings(termFile(normalizedTerm));
        books.addAll(pendingPostings.getOrDefault(normalizedTerm, Collections.emptySortedSet()));
        return Collections.unmodifiableSortedSet(books);
    }

    @Override
    public synchronized void flush() throws IOException {
        for (Map.Entry<String, SortedSet<Integer>> entry : pendingPostings.entrySet())
            mergeIntoFile(termFile(entry.getKey()), entry.getValue());
        pendingPostings.clear();
    }

    private Path termFile(String term) {
        return rootDirectory.resolve(folderName(term)).resolve(fileName(term));
    }

    private static String folderName(String term) {
        String firstCharacter = new String(Character.toChars(term.codePointAt(0)));
        return firstCharacter.toUpperCase(Locale.ROOT);
    }

    private static String fileName(String term) {
        String name = WINDOWS_RESERVED_NAMES.contains(term) ? "_" + term : term;
        return name + ".txt";
    }

    private static void mergeIntoFile(Path file, Set<Integer> newBookIds) throws IOException {
        SortedSet<Integer> books = readPostings(file);
        if (!books.addAll(newBookIds)) return;
        Files.createDirectories(file.toAbsolutePath().getParent());
        writeAtomically(file, toLines(books));
    }

    private static SortedSet<Integer> readPostings(Path file) {
        if (!Files.exists(file)) return new TreeSet<>();
        try {
            return Files.readAllLines(file, StandardCharsets.UTF_8).stream()
                    .map(String::strip)
                    .filter(line -> !line.isEmpty())
                    .map(Integer::parseInt)
                    .collect(Collectors.toCollection(TreeSet::new));
        } catch (IOException e) {
            throw new UncheckedIOException("Error reading " + file, e);
        }
    }

    private static String toLines(SortedSet<Integer> books) {
        return books.stream()
                .map(String::valueOf)
                .collect(Collectors.joining("\n", "", "\n"));
    }

    private static void writeAtomically(Path target, String content) throws IOException {
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.writeString(tmp, content, StandardCharsets.UTF_8);
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
