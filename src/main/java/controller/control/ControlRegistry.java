package controller.control;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Collections;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.stream.Collectors;

public class ControlRegistry {

    private static final String DOWNLOADED_FILE = "downloaded_books.txt";
    private static final String INDEXED_FILE = "indexed_books.txt";

    private final Path downloadedFile;
    private final Path indexedFile;
    private final SortedSet<Integer> downloaded;
    private final SortedSet<Integer> indexed;

    public ControlRegistry(Path controlDirectory) throws IOException {
        Files.createDirectories(controlDirectory);
        this.downloadedFile = controlDirectory.resolve(DOWNLOADED_FILE);
        this.indexedFile = controlDirectory.resolve(INDEXED_FILE);
        this.downloaded = load(downloadedFile);
        this.indexed = load(indexedFile);
    }

    public synchronized SortedSet<Integer> downloaded() {
        return Collections.unmodifiableSortedSet(new TreeSet<>(downloaded));
    }

    public synchronized SortedSet<Integer> indexed() {
        return Collections.unmodifiableSortedSet(new TreeSet<>(indexed));
    }

    public synchronized SortedSet<Integer> pendingToIndex() {
        SortedSet<Integer> pending = new TreeSet<>(downloaded);
        pending.removeAll(indexed);
        return Collections.unmodifiableSortedSet(pending);
    }

    public synchronized boolean isDownloaded(int bookId) {
        return downloaded.contains(bookId);
    }

    public synchronized void markDownloaded(int bookId) throws IOException {
        if (downloaded.contains(bookId)) return;
        append(downloadedFile, bookId);
        downloaded.add(bookId);
    }

    public synchronized void markIndexed(int bookId) throws IOException {
        if (indexed.contains(bookId)) return;
        append(indexedFile, bookId);
        indexed.add(bookId);
    }

    public synchronized void forgetDownloaded(int bookId) throws IOException {
        if (!downloaded.remove(bookId)) return;
        rewrite(downloadedFile, downloaded);
    }

    private static SortedSet<Integer> load(Path file) throws IOException {
        SortedSet<Integer> ids = new TreeSet<>();
        if (!Files.exists(file)) return ids;
        String content = Files.readString(file, StandardCharsets.UTF_8);
        int lastNewline = content.lastIndexOf('\n');
        if (lastNewline < content.length() - 1) discardIncompleteLastLine(file, lastNewline + 1);
        for (String line : content.substring(0, lastNewline + 1).split("\n")) {
            Integer id = parse(line.strip());
            if (id != null) ids.add(id);
        }
        return ids;
    }

    private static void discardIncompleteLastLine(Path file, long completeLength) throws IOException {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE)) {
            channel.truncate(completeLength);
            channel.force(true);
        }
    }

    private static Integer parse(String value) {
        try {
            return value.isEmpty() ? null : Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static void append(Path file, int bookId) throws IOException {
        byte[] line = (bookId + "\n").getBytes(StandardCharsets.UTF_8);
        try (FileChannel channel = FileChannel.open(file,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
            channel.write(ByteBuffer.wrap(line));
            channel.force(true);
        }
    }

    private static void rewrite(Path file, SortedSet<Integer> ids) throws IOException {
        String content = ids.stream().map(id -> id + "\n").collect(Collectors.joining());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, content, StandardCharsets.UTF_8);
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
