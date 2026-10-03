package controller.feeder.gutenberg;

import controller.feeder.BookCrawler;
import model.RawBook; // Usamos tu modelo existente

import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;
import java.util.function.IntPredicate;

public class GutenbergCrawler implements BookCrawler {

    private static final long MIN_DELAY_MS = 768;
    private static final long MAX_DELAY_MS = 1024;

    private final Object rateLimitLock = new Object();

    public void crawl(int startBookId, int endBookId, IntPredicate filter, Consumer<RawBook> rawBookConsumer) {
        for (int bookId = startBookId; bookId <= endBookId; bookId++) {
            if (filter != null && !filter.test(bookId)) {
                continue;
            }

            try {
                enforceRateLimit();
                String responseBody = GutenbergBookDownloader.downloadBook(bookId);

                if (responseBody != null && !responseBody.isEmpty()) {
                    // Empaquetamos el id y el contenido en el RawBook
                    rawBookConsumer.accept(new RawBook(bookId, responseBody));
                }
            } catch (Exception e) {
                System.err.printf("Error processing book ID %d: %s%n", bookId, e.getMessage());
            }
        }
    }

    private void enforceRateLimit() {
        synchronized (rateLimitLock) {
            try {
                long delay = ThreadLocalRandom.current().nextLong(MIN_DELAY_MS, MAX_DELAY_MS + 1);
                Thread.sleep(delay);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}