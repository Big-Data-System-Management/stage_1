import controller.Controller;
import controller.datamart.MetadataRepository;
import controller.datamart.SqliteMetadataRepository;
import controller.feeder.BookCrawler;
import controller.feeder.BookFeeder;
import controller.feeder.gutenberg.GutenbergBookProcessor;
import controller.feeder.gutenberg.GutenbergCrawler;
import model.OverwriteMode;
import controller.store.DatalakeLocalStoreTimeHierarchy;
import controller.store.Store;
import java.nio.file.Path;

public class Main {
    public static void main(String[] args) throws Exception{
        BookCrawler crawler = new GutenbergCrawler();
        BookFeeder feeder = new GutenbergBookProcessor();
        Store store = new DatalakeLocalStoreTimeHierarchy("datalake");
        try (MetadataRepository metadata = new SqliteMetadataRepository(Path.of("datamarts", "metadata.db"))) {
            Controller controller = new Controller(crawler, feeder, store, metadata, OverwriteMode.SKIP_IF_EXISTS);
            controller.executeBatch(1, 1000);
        }
    }
}