import controller.Controller;
import controller.feeder.BookCrawler;
import controller.feeder.BookFeeder;
import controller.feeder.gutenberg.GutenbergBookProcessor;
import controller.feeder.gutenberg.GutenbergCrawler;
import model.OverwriteMode;
import controller.store.DatalakeLocalStoreTimeHierarchy;
import controller.store.Store;

public class Main {
    public static void main(String[] args) {
        BookCrawler crawler = new GutenbergCrawler();
        BookFeeder feeder = new GutenbergBookProcessor();
        Store store = new DatalakeLocalStoreTimeHierarchy("datalake");
        Controller controller = new Controller(crawler, feeder, store, OverwriteMode.SKIP_IF_EXISTS);
        controller.executeBatch(1, 1000);
    }
}