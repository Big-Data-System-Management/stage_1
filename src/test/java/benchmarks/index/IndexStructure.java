package benchmarks.index;

import controller.index.HierarchicalFolderIndex;
import controller.index.InvertedIndex;
import controller.index.MongoInvertedIndex;
import controller.index.MonolithicJsonIndex;
import controller.index.Tokenizer;

import java.nio.file.Path;

public enum IndexStructure {
    JSON, FOLDER, MONGO;

    public InvertedIndex create(Path workDir, Tokenizer tokenizer) {
        return switch (this) {
            case JSON -> new MonolithicJsonIndex(workDir.resolve("inverted_index.json"), tokenizer);
            case FOLDER -> new HierarchicalFolderIndex(workDir.resolve("inverted_index"), tokenizer);
            case MONGO -> new MongoInvertedIndex(IndexBenchmarkSupport.MONGO_URI, IndexBenchmarkSupport.MONGO_DATABASE,
                    IndexBenchmarkSupport.MONGO_COLLECTION, tokenizer);
        };
    }
}
