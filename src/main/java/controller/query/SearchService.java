package controller.query;

import controller.datamart.MetadataRepository;
import controller.index.InvertedIndex;
import model.BookMetadata;

import java.util.List;

public class SearchService {

    private static final String UNKNOWN = "Unknown";

    private final InvertedIndex index;
    private final MetadataRepository metadata;

    public SearchService(InvertedIndex index, MetadataRepository metadata) {
        this.index = index;
        this.metadata = metadata;
    }

    public List<BookMetadata> search(String term) {
        return index.search(term).stream()
                .map(bookId -> metadata.findById(bookId)
                        .orElse(new BookMetadata(bookId, UNKNOWN, UNKNOWN, UNKNOWN, null)))
                .toList();
    }
}
