import controller.Controller;
import controller.control.ControlLayer;
import controller.control.ControlRegistry;
import controller.datamart.MetadataRepository;
import controller.datamart.SqliteMetadataRepository;
import controller.feeder.BookCrawler;
import controller.feeder.BookFeeder;
import controller.feeder.gutenberg.GutenbergBookProcessor;
import controller.feeder.gutenberg.GutenbergCrawler;
import controller.index.InvertedIndex;
import controller.index.MonolithicJsonIndex;
import controller.index.Tokenizer;
import model.OverwriteMode;
import controller.store.DatalakeLocalStoreTimeHierarchy;
import controller.store.Store;
import java.nio.file.Path;

public class Main {
    public static void main(String[] args) throws Exception{
        BookCrawler crawler = new GutenbergCrawler();
        BookFeeder feeder = new GutenbergBookProcessor();
        Store store = new DatalakeLocalStoreTimeHierarchy("datalake");
        InvertedIndex index = new MonolithicJsonIndex(Path.of("datamarts", "inverted_index.json"), new Tokenizer());
        ControlRegistry registry = new ControlRegistry(Path.of("control"));
        try (MetadataRepository metadata = new SqliteMetadataRepository(Path.of("datamarts", "metadata.db"))) {
            Controller controller = new Controller(crawler, feeder, store, metadata, OverwriteMode.SKIP_IF_EXISTS);
            new ControlLayer(controller, store, index, registry, 1, 1000, 10).run();
        }
    }
}
