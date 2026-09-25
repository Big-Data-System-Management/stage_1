package controller.store;

import model.Book;
import java.io.IOException;

public interface Store {
    void storeData(Book book) throws IOException;
    boolean exists(int bookId);
}