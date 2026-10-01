package controller.index;

import java.io.IOException;
import java.util.Set;

public interface InvertedIndex {
    void indexBook(int bookId, String body);
    Set<Integer> search(String term);
    void flush() throws IOException;
}
