package controller;

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
    private final Consumer<Book> bookConsumer;
    private final Consumer<RawBook> rawBookConsumer;

    private final ScheduledExecutorService scheduler;
    private final OverwriteMode overwriteMode;

    public Controller(BookCrawler crawler, BookFeeder feeder, Store store) {
        this(crawler, feeder, store, OverwriteMode.SKIP_IF_EXISTS);
    }

    public Controller(BookCrawler crawler, BookFeeder feeder, Store store, OverwriteMode overwriteMode) {
        this.crawler = crawler;
        this.store = store;
        this.overwriteMode = overwriteMode;
        this.scheduler = Executors.newSingleThreadScheduledExecutor();

        this.bookConsumer = book -> {
            try {
                store.storeData(book);
                System.out.println("Libro guardado en Data Lake ID: " + book.id());
            } catch (IOException e) {
                System.err.println("Error al guardar libro ID " + book.id() + ": " + e.getMessage());
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
                        System.err.println("Error en la ejecución temporizada: " + e.getMessage());
                    }
                },
                0,
                24,
                TimeUnit.HOURS
        );
    }

    public void executeBatch(int startId, int endId) {
        System.out.println("=== Iniciando ciclo de extracción ===");
        IntPredicate shouldDownloadFilter = bookId -> {
            if (this.overwriteMode == OverwriteMode.OVERWRITE) {
                return true;
            }
            return !store.exists(bookId);
        };
        crawler.crawl(startId, endId, shouldDownloadFilter, rawBookConsumer);
        System.out.println("=== Ciclo de extracción finalizado ===");
    }
}