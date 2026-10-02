package controller.feeder.gutenberg;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GutenbergBookDownloaderTest {

    private static final String TEXT = "*** START OF THE PROJECT GUTENBERG EBOOK ***\nCafé, naïve, Straße\n";

    @Test
    void decodesPlainUtf8Text() throws Exception {
        assertEquals(TEXT, GutenbergBookDownloader.decode(TEXT.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void decompressesGzipResponses() throws Exception {
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(compressed)) {
            gzip.write(TEXT.getBytes(StandardCharsets.UTF_8));
        }

        assertEquals(TEXT, GutenbergBookDownloader.decode(compressed.toByteArray()));
    }
}
