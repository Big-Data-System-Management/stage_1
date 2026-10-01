package controller.store;

import model.Book;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

public class CompositeStore implements Store {

    private final List<Store> stores;

    public CompositeStore(List<Store> stores) {
        this.stores = stores;
    }

    public CompositeStore(Store... stores) {
        this.stores = List.of(stores);
    }

    @Override
    public Path storeData(Book book) throws IOException {
        for (Store store : stores) {
            store.storeData(book);
        }
        return Path.of("");
    }

    @Override
    public boolean exists(int bookId) {
        return stores.stream().allMatch(store -> store.exists(bookId));
    }

    @Override
    public Book getBook(int id) {
        for (Store store : stores) {
            if (store.exists(id)) {
                Book book = store.getBook(id);
                if (book != null) {
                    return book;
                }
            }
        }
        return null;
    }
}