package controller;

import controller.datamart.MetadataRepository;
import java.nio.file.Path;
import controller.feeder.BookCrawler;
import controller.feeder.BookFeeder;
import model.Book;
import model.OverwriteMode;
import model.RawBook;
import controller.store.Store;

import java.io.IOException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.IntPredicate;

public class Controller {

    private final BookCrawler crawler;
    private final Store store;
    private final MetadataExtractor metadataExtractor;
    private final Consumer<Book> bookConsumer;
    private final Consumer<RawBook> rawBookConsumer;

    private final ScheduledExecutorService scheduler;
    private final OverwriteMode overwriteMode;

    public Controller(BookCrawler crawler, BookFeeder feeder, Store store, OverwriteMode overwriteMode) {
        this(crawler, feeder, store, null, overwriteMode);
    }

    public Controller(BookCrawler crawler, BookFeeder feeder, Store store,
                      MetadataRepository metadataRepository, OverwriteMode overwriteMode) {
        this.crawler = crawler;
        this.store = store;
        this.overwriteMode = overwriteMode;
        this.metadataExtractor = metadataRepository == null ? null : new MetadataExtractor(metadataRepository);

        this.scheduler = Executors.newSingleThreadScheduledExecutor();

        this.bookConsumer = book -> {
            try {
                Path bodyPath = store.storeData(book);
                System.out.println("Book stored in data lake, ID: " + book.id());
                if (metadataExtractor != null) metadataExtractor.extractAndProcess(book, bodyPath);
            } catch (IOException e) {
                System.err.println("Error storing book ID " + book.id() + ": " + e.getMessage());
            }
        };
        this.rawBookConsumer = (rb) -> feeder.processData(rb, bookConsumer);
    }

    public void startDailyScheduler(int startId, int endId) {
        scheduler.scheduleAtFixedRate(
                () -> {
                    try {
                        executeBatch(startId, endId);
                    } catch (Exception e) {
                        System.err.println("Error in scheduled execution: " + e.getMessage());
                    }
                },
                0,
                24,
                TimeUnit.HOURS
        );
    }

    public void executeBatch(int startId, int endId) {
        System.out.println("=== Starting extraction cycle ===");
        IntPredicate shouldDownloadFilter = bookId -> {
            if (this.overwriteMode == OverwriteMode.OVERWRITE) {
                return true;
            }
            return !store.exists(bookId);
        };
        crawler.crawl(startId, endId, shouldDownloadFilter, rawBookConsumer);
        System.out.println("=== Extraction cycle finished ===");
    }
}