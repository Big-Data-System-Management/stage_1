package controller.control;

import controller.Controller;
import controller.feeder.BookCrawler;
import controller.feeder.gutenberg.GutenbergBookProcessor;
import controller.index.InvertedIndex;
import controller.index.MonolithicJsonIndex;
import controller.index.Tokenizer;
import controller.store.DatalakeLocalStoreBookHierarchy;
import controller.store.Store;
import model.Book;
import model.OverwriteMode;
import model.RawBook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.IntPredicate;

import static controller.control.ControlLayer.StepResult.DOWNLOADED;
import static controller.control.ControlLayer.StepResult.FINISHED;
import static controller.control.ControlLayer.StepResult.INDEXED;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ControlLayerTest {

    private static final Map<Integer, String> GUTENBERG = Map.of(
            1, gutenbergText(1, "whale ocean"),
            2, gutenbergText(2, "island ocean"),
            4, gutenbergText(4, "whale storm"),
            5, gutenbergText(5, "island storm"));

    @TempDir
    Path tempDir;

    private final FakeCrawler crawler = new FakeCrawler();

    @Test
    void downloadsAndIndexesEveryAvailableBook() throws Exception {
        ControlRegistry registry = registry();
        InvertedIndex index = index();

        controlLayer(store(), index, registry, 2).run();

        assertEquals(Set.of(1, 2, 4, 5), registry.downloaded());
        assertEquals(Set.of(1, 2, 4, 5), registry.indexed());
        assertEquals(Set.of(1, 4), index.search("whale"));
        assertEquals(Set.of(1, 2), index.search("ocean"));
    }

    @Test
    void indexesInBatchesOfTheGivenSize() throws Exception {
        ControlLayer controlLayer = controlLayer(store(), index(), registry(), 2);

        List<ControlLayer.StepResult> steps = new ArrayList<>();
        ControlLayer.StepResult step;
        do {
            step = controlLayer.step();
            steps.add(step);
        } while (step != FINISHED);

        assertEquals(List.of(DOWNLOADED, DOWNLOADED, INDEXED, DOWNLOADED, DOWNLOADED, INDEXED, FINISHED), steps);
    }

    @Test
    void resumingAfterARunDoesNotDownloadOrIndexAgain() throws Exception {
        controlLayer(store(), index(), registry(), 2).run();
        int downloadsInFirstRun = crawler.downloads.size();

        ControlRegistry registry = registry();
        controlLayer(store(), index(), registry, 2).run();

        assertEquals(4, downloadsInFirstRun);
        assertEquals(4, crawler.downloads.size());
        assertEquals(Set.of(1, 2, 4, 5), registry.indexed());
    }

    @Test
    void registersABookAlreadyInTheDatalakeWithoutDownloadingIt() throws Exception {
        Store store = store();
        store.storeData(new Book(1, "Title: Book 1", "whale ocean"));
        ControlRegistry registry = registry();

        controlLayer(store, index(), registry, 2).run();

        assertEquals(List.of(2, 4, 5), crawler.downloads);
        assertEquals(Set.of(1, 2, 4, 5), registry.indexed());
    }

    @Test
    void resumesIndexingBooksDownloadedBeforeACrash() throws Exception {
        Store store = store();
        ControlRegistry registry = registry();
        ControlLayer beforeCrash = controlLayer(store, index(), registry, 10);
        beforeCrash.step();
        beforeCrash.step();

        ControlRegistry afterCrash = registry();
        InvertedIndex index = index();
        controlLayer(store(), index, afterCrash, 10).run();

        assertEquals(Set.of(1, 2, 4, 5), afterCrash.indexed());
        assertEquals(Set.of(1, 2), index.search("ocean"));
        assertEquals(4, crawler.downloads.size());
    }

    @Test
    void forgetsARegisteredBookMissingFromTheDatalake() throws Exception {
        ControlRegistry registry = registry();
        registry.markDownloaded(7);

        controlLayer(store(), index(), registry, 1).run();

        assertEquals(Set.of(1, 2, 4, 5), registry.downloaded());
        assertEquals(Set.of(1, 2, 4, 5), registry.indexed());
    }

    private ControlLayer controlLayer(Store store, InvertedIndex index, ControlRegistry registry, int batchSize) {
        Controller downloader = new Controller(crawler, new GutenbergBookProcessor(), store, OverwriteMode.SKIP_IF_EXISTS);
        return new ControlLayer(downloader, store, index, registry, 1, 5, batchSize);
    }

    private Store store() {
        return new DatalakeLocalStoreBookHierarchy(tempDir.resolve("datalake").toString());
    }

    private InvertedIndex index() {
        return new MonolithicJsonIndex(tempDir.resolve("datamarts").resolve("inverted_index.json"), new Tokenizer());
    }

    private ControlRegistry registry() throws Exception {
        return new ControlRegistry(tempDir.resolve("control"));
    }

    private static String gutenbergText(int id, String body) {
        return "Title: Book " + id + "\nLanguage: English\n"
                + "*** START OF THE PROJECT GUTENBERG EBOOK BOOK " + id + " ***\n"
                + body + "\n"
                + "*** END OF THE PROJECT GUTENBERG EBOOK BOOK " + id + " ***\n";
    }

    private static class FakeCrawler implements BookCrawler {

        private final List<Integer> downloads = new ArrayList<>();

        @Override
        public void crawl(int startBookId, int endBookId, IntPredicate filter, Consumer<RawBook> rawBookConsumer) {
            for (int id = startBookId; id <= endBookId; id++) {
                if (filter != null && !filter.test(id)) continue;
                String text = GUTENBERG.get(id);
                if (text == null) continue;
                downloads.add(id);
                rawBookConsumer.accept(new RawBook(id, text));
            }
        }
    }
}
