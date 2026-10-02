import controller.datamart.MetadataRepository;
import controller.datamart.SqliteMetadataRepository;
import controller.index.MonolithicJsonIndex;
import controller.index.Tokenizer;
import controller.query.SearchService;
import model.BookMetadata;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Scanner;

public class Search {

    public static void main(String[] args) throws Exception {
        MonolithicJsonIndex index = new MonolithicJsonIndex(Path.of("datamarts", "inverted_index.json"), new Tokenizer());
        try (MetadataRepository metadata = new SqliteMetadataRepository(Path.of("datamarts", "metadata.db"))) {
            SearchService service = new SearchService(index, metadata);
            if (args.length > 0) {
                print(String.join(" ", args), service.search(String.join(" ", args)));
                return;
            }
            Scanner scanner = new Scanner(System.in, StandardCharsets.UTF_8);
            while (true) {
                System.out.print("Buscar (Enter vacío para salir): ");
                if (!scanner.hasNextLine()) return;
                String term = scanner.nextLine().strip();
                if (term.isEmpty()) return;
                print(term, service.search(term));
            }
        }
    }

    private static void print(String term, List<BookMetadata> results) {
        System.out.printf("%d libros contienen \"%s\"%n", results.size(), term);
        for (BookMetadata book : results)
            System.out.printf("  [%d] %s | %s | %s | %s%n",
                    book.bookId(), book.title(), book.author(), book.language(), book.bodyPath());
    }
}
