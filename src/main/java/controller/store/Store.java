package controller.store;

import model.Book;
import java.io.IOException;
import java.nio.file.Path;

public interface Store {
    Path storeData(Book book) throws IOException;
    boolean exists(int bookId);
}