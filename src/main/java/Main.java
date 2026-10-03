import controller.Controller;
import controller.control.ControlLayer;
import controller.control.ControlRegistry;
import controller.datamart.MetadataRepository;
import controller.datamart.SqliteMetadataRepository;
import controller.feeder.BookCrawler;
import controller.feeder.BookFeeder;
import controller.feeder.LocalBookCrawler;
import controller.feeder.gutenberg.GutenbergBookProcessor;
import controller.feeder.gutenberg.GutenbergCrawler;
import controller.index.InvertedIndex;
import controller.index.MonolithicJsonIndex;
import controller.index.Tokenizer;
import model.OverwriteMode;
import controller.store.DatalakeLocalStoreTimeHierarchy;
import controller.store.Store;
import java.nio.file.Path;
import java.util.SortedSet;

public class Main {

    private static final int FIRST_BOOK_ID = 1;
    private static final int LAST_BOOK_ID = 1000;
    private static final int INDEX_BATCH_SIZE = 10;

    public static void main(String[] args) throws Exception{
        BookCrawler crawler = new GutenbergCrawler();
        int firstBookId = FIRST_BOOK_ID;
        int lastBookId = LAST_BOOK_ID;
        if (args.length > 0) {
            LocalBookCrawler localCrawler = new LocalBookCrawler(Path.of(args[0]));
            SortedSet<Integer> bookIds = localCrawler.availableBookIds();
            if (bookIds.isEmpty()) throw new IllegalArgumentException("No pg<ID>.txt books found in " + args[0]);
            crawler = localCrawler;
            firstBookId = bookIds.first();
            lastBookId = bookIds.last();
        }
        BookFeeder feeder = new GutenbergBookProcessor();
        Store store = new DatalakeLocalStoreTimeHierarchy("datalake");
        InvertedIndex index = new MonolithicJsonIndex(Path.of("datamarts", "inverted_index.json"), new Tokenizer());
        ControlRegistry registry = new ControlRegistry(Path.of("control"));
        try (MetadataRepository metadata = new SqliteMetadataRepository(Path.of("datamarts", "metadata.db"))) {
            Controller controller = new Controller(crawler, feeder, store, metadata, OverwriteMode.SKIP_IF_EXISTS);
            new ControlLayer(controller, store, index, registry, firstBookId, lastBookId, INDEX_BATCH_SIZE).run();
        }
    }
}
