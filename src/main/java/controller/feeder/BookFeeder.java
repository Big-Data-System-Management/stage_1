package controller.feeder;

import model.Book;
import model.RawBook;
import java.util.function.Consumer;

public interface BookFeeder {
    void processData(RawBook rawBook, Consumer<Book> bookConsumer);
}