package controller.control;

import controller.Controller;
import controller.index.InvertedIndex;
import controller.store.Store;
import model.Book;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.SortedSet;

public class ControlLayer {

    public enum StepResult { DOWNLOADED, INDEXED, FINISHED }

    private final Controller downloader;
    private final Store store;
    private final InvertedIndex index;
    private final ControlRegistry registry;
    private final int lastBookId;
    private final int indexBatchSize;
    private int nextCandidate;

    public ControlLayer(Controller downloader, Store store, InvertedIndex index, ControlRegistry registry,
                        int firstBookId, int lastBookId, int indexBatchSize) {
        if (indexBatchSize < 1) throw new IllegalArgumentException("indexBatchSize must be at least 1");
        this.downloader = downloader;
        this.store = store;
        this.index = index;
        this.registry = registry;
        this.nextCandidate = firstBookId;
        this.lastBookId = lastBookId;
        this.indexBatchSize = indexBatchSize;
    }

    public void run() throws IOException {
        StepResult result;
        do {
            result = step();
        } while (result != StepResult.FINISHED);
        System.out.println("[CONTROL] Pipeline finished: " + registry.indexed().size() + " books indexed.");
    }

    public StepResult step() throws IOException {
        SortedSet<Integer> pending = registry.pendingToIndex();
        if (pending.size() >= indexBatchSize) {
            indexBatch(pending);
            return StepResult.INDEXED;
        }
        if (downloadNext()) return StepResult.DOWNLOADED;
        if (!pending.isEmpty()) {
            indexBatch(pending);
            return StepResult.INDEXED;
        }
        return StepResult.FINISHED;
    }

    private boolean downloadNext() throws IOException {
        while (nextCandidate <= lastBookId) {
            int bookId = nextCandidate++;
            if (registry.isDownloaded(bookId)) continue;
            if (!store.exists(bookId)) downloader.executeBatch(bookId, bookId);
            if (store.exists(bookId)) {
                registry.markDownloaded(bookId);
                System.out.println("[CONTROL] Book " + bookId + " downloaded.");
                return true;
            }
        }
        return false;
    }

    private void indexBatch(SortedSet<Integer> pending) throws IOException {
        List<Integer> indexedBooks = new ArrayList<>();
        for (int bookId : pending.stream().limit(indexBatchSize).toList()) {
            Book book = store.getBook(bookId);
            if (book == null) {
                registry.forgetDownloaded(bookId);
                System.err.println("[CONTROL] Book " + bookId + " registered but not found in the datalake; it will be downloaded again.");
                continue;
            }
            index.indexBook(bookId, book.body());
            indexedBooks.add(bookId);
        }
        index.flush();
        for (int bookId : indexedBooks) registry.markIndexed(bookId);
        System.out.println("[CONTROL] Indexed " + indexedBooks.size() + " books: " + indexedBooks);
    }
}
