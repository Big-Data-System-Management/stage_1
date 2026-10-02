package controller.feeder.gutenberg;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.zip.GZIPInputStream;

public final class GutenbergBookDownloader {

    private static final String URL_PATTERN = "https://www.gutenberg.org/cache/epub/%d/pg%d.txt";

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private GutenbergBookDownloader() {
        throw new UnsupportedOperationException("Clase de utilidad estática");
    }

    public static String downloadBook(int bookId) throws IOException, InterruptedException {
        HttpResponse<byte[]> response = sendRequest(bookId);
        int statusCode = response.statusCode();

        if (statusCode == 200) {
            return decode(response.body());
        }

        manageOtherStatusCodes(statusCode, bookId);
        return null;
    }

    static String decode(byte[] content) throws IOException {
        if (isGzip(content)) {
            try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(content))) {
                content = gzip.readAllBytes();
            }
        }
        return new String(content, StandardCharsets.UTF_8);
    }

    private static boolean isGzip(byte[] content) {
        return content.length >= 2 && (content[0] & 0xFF) == 0x1F && (content[1] & 0xFF) == 0x8B;
    }

    private static HttpResponse<byte[]> sendRequest(int bookId) throws IOException, InterruptedException {
        String url = String.format(URL_PATTERN, bookId, bookId);
        HttpRequest request = createRequest(url);
        return HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofByteArray());
    }

    private static HttpRequest createRequest(String url) {
        return HttpRequest.newBuilder()
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
    }

    private static void manageOtherStatusCodes(int statusCode, int bookId) throws IOException, InterruptedException {
        try {
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
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); // Restablece el flag de interrupción
            throw e;
        }

        throw new IOException("Error HTTP no clasificado: " + statusCode);
    }
}