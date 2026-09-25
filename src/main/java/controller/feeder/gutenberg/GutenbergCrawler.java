package controller.feeder.gutenberg;

import controller.feeder.BookCrawler;
import model.RawBook;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.IntPredicate;

public class GutenbergCrawler implements BookCrawler {

    private static final long MIN_DELAY_MS = 768;
    private static final long MAX_DELAY_MS = 1024;
    private static final String URL_PATTERN = "https://www.gutenberg.org/cache/epub/%d/pg%d.txt";

    private final HttpClient httpClient;
    private final Object rateLimitLock = new Object();

    public GutenbergCrawler() {
        this.httpClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Override
    public void crawl(int startBookId, int endBookId, IntPredicate filter, Consumer<RawBook> rawBookConsumer) {
        for (int bookId = startBookId; bookId <= endBookId; bookId++) {
            if (filter != null && !filter.test(bookId)) continue;
            try {
                enforceRateLimit();
                downloadBook(bookId).ifPresent(rawBookConsumer::accept);
            } catch (Exception e) {
                System.err.printf("Error al procesar el libro ID %d: %s%n", bookId, e.getMessage());
            }
        }
    }

    private void submitToThreadPool(int currentId, ExecutorService workerPool, IntPredicate filter, Consumer<RawBook> rawBookConsumer) {
        workerPool.submit(() -> {

        });
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

    private Optional<RawBook> downloadBook(int bookId) throws IOException, InterruptedException {
        HttpResponse<String> response = sendRequest(bookId);
        int responseStatusCode = response.statusCode();
        if (responseStatusCode == 200) return Optional.of(new RawBook(bookId, response.body()));
        manageOtherStatusCodes(responseStatusCode, bookId);
        return Optional.empty();
    }

    private static void awaitTermination(ExecutorService workerPool) {
        try {
            workerPool.awaitTermination(24, TimeUnit.HOURS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private HttpResponse<String> sendRequest(int bookId) throws IOException, InterruptedException {
        String url = String.format(URL_PATTERN, bookId, bookId);
        HttpRequest request = createRequest(url);
        return this.httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static HttpRequest createRequest(String url) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8")
                .header("Accept-Language", "es-ES,es;q=0.9,en-US;q=0.8,en;q=0.7")
                .header("Cache-Control", "max-age=0")
                .header("Upgrade-Insecure-Requests", "1")
                .header("Sec-Ch-Ua", "\"Chromium\";v=\"122\", \"Not(A:Brand\";v=\"24\", \"Google Chrome\";v=\"122\"")
                .header("Sec-Ch-Ua-Mobile", "?0")
                .header("Sec-Ch-Ua-Platform", "\"Windows\"")
                .header("Sec-Fetch-Dest", "document")
                .header("Sec-Fetch-Mode", "navigate")
                .header("Sec-Fetch-Site", "none")
                .header("Sec-Fetch-User", "?1")
                .GET()
                .build();
        return request;
    }

    private void manageOtherStatusCodes(int statusCode, int bookId) throws InterruptedException, IOException {

        if (statusCode == 403) {
            System.err.printf("¡ALERTA CRÍTICA HTTP 403 en ID %d! Acceso denegado/Posible baneo. Pausando 2 minutos...%n", bookId);
            Thread.sleep(120_000);
            throw new IOException("Acceso prohibido (HTTP 403)");
        }

        if (statusCode == 429) {
            System.err.printf("¡ALERTA HTTP 429 en ID %d! Servidor saturado. Pausando 1 minuto...%n", bookId);
            Thread.sleep(60_000);
            throw new IOException("Demasiadas peticiones (HTTP 429)");
        }

        if (statusCode >= 500 && statusCode < 600) {
            System.err.printf("Error del servidor HTTP %d en ID %d. Pausando 10 segundos...%n", statusCode, bookId);
            Thread.sleep(10_000);
            throw new IOException("Error interno del servidor (HTTP " + statusCode + ")");
        }

        throw new IOException("Error HTTP no clasificado: " + statusCode);
    }
}