package controller.feeder;

import model.RawBook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LocalBookCrawlerTest {

    @TempDir
    Path tempDir;

    @Test
    void listsTheIdsOfTheBooksInTheFolder() throws Exception {
        write("pg12.txt", "a");
        write("pg5.txt", "b");
        write("notes.txt", "c");
        write("pg7.missing", "");

        assertEquals(Set.of(5, 12), new LocalBookCrawler(tempDir).availableBookIds());
    }

    @Test
    void readsTheBooksInTheRangeThatPassTheFilter() throws Exception {
        write("pg1.txt", "Café");
        write("pg2.txt", "skipped");
        write("pg9.txt", "out of range");
        List<RawBook> books = new ArrayList<>();

        new LocalBookCrawler(tempDir).crawl(1, 5, bookId -> bookId != 2, books::add);

        assertEquals(List.of(new RawBook(1, "Café")), books);
    }

    private void write(String name, String content) throws Exception {
        Files.writeString(tempDir.resolve(name), content, StandardCharsets.UTF_8);
    }
}
