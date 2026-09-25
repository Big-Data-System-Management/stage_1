package controller.feeder;

import model.RawBook;

import java.util.function.Consumer;
import java.util.function.IntPredicate;

public interface BookCrawler {
    void crawl(int startBookId, int endBookId, IntPredicate filter, Consumer<RawBook> rawBookConsumer);
}
