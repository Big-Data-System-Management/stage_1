package benchmarks;

import controller.Controller;
import controller.feeder.BookCrawler;
import controller.feeder.BookFeeder;
import controller.feeder.gutenberg.GutenbergBookProcessor;
import controller.feeder.gutenberg.GutenbergCrawler;
import controller.store.CompositeStore;
import controller.store.DatalakeLocalStoreBookHierarchy;
import controller.store.DatalakeLocalStoreIdRangeHierarchy;
import controller.store.DatalakeLocalStoreTimeHierarchy;
import controller.store.Store;
import model.OverwriteMode;

import java.util.List;

public class initData {
    public static void main(String[] args) {
        List<Store> storeList = List.of(
                new DatalakeLocalStoreBookHierarchy("datalakeBookHierarchy"),
                new DatalakeLocalStoreIdRangeHierarchy("datalakeIdRangeHierarchy"),
                new DatalakeLocalStoreTimeHierarchy("datalakeTimeHierarchy")
        );

        Store compositeStore = new CompositeStore(storeList);

        BookCrawler crawler = new GutenbergCrawler();
        BookFeeder feeder = new GutenbergBookProcessor();
        Controller controller = new Controller(crawler, feeder, compositeStore, OverwriteMode.SKIP_IF_EXISTS);
        controller.executeBatch(1, 250);
    }
}