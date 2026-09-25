package controller.store;

import model.Book;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

public class DatalakeLocalStoreIdRangeHierarchy implements Store {

    private final String baseDataLakePath;
    private final Set<Integer> existingBookIds = ConcurrentHashMap.newKeySet();
    private static final int BATCH_SIZE = 1000;

    public DatalakeLocalStoreIdRangeHierarchy(String baseDataLakePath) {
        this.baseDataLakePath = baseDataLakePath;
        this.indexExistingBooks();
    }

    private void indexExistingBooks() {
        Path basePath = Paths.get(this.baseDataLakePath);
        if (!Files.exists(basePath)) return;
        try (Stream<Path> stream = Files.walk(basePath)) {
            extractAndAddExistingIds(stream);
            System.out.printf("[Store Batch-Hierarchy] Índice cargado en RAM: %d libros detectados.%n", existingBookIds.size());
        } catch (IOException e) {
            System.err.println("Error indexando Data Lake: " + e.getMessage());
        }
    }

    private void extractAndAddExistingIds(Stream<Path> stream) {
        stream.filter(Files::isRegularFile)
                .map(path -> path.getFileName().toString())
                .filter(name -> name.endsWith(".body.txt"))
                .forEach(name -> {
                    try {
                        int id = Integer.parseInt(name.replace(".body.txt", ""));
                        existingBookIds.add(id);
                    } catch (NumberFormatException ignored) {}
                });
    }

    @Override
    public boolean exists(int bookId) {
        return existingBookIds.contains(bookId);
    }

    @Override
    public void storeData(Book book) throws IOException {
        int batchNumber = book.id() / BATCH_SIZE;
        String batchFolderName = String.format("batch_%d_to_%d",
                batchNumber * BATCH_SIZE,
                ((batchNumber + 1) * BATCH_SIZE) - 1);

        Path targetDir = Paths.get(this.baseDataLakePath, batchFolderName);
        Files.createDirectories(targetDir);

        Path headerPath = targetDir.resolve(book.id() + ".header.txt");
        Path bodyPath = targetDir.resolve(book.id() + ".body.txt");

        writeAtomically(headerPath, book.head());
        writeAtomically(bodyPath, book.body());
        existingBookIds.add(book.id());
    }

    private static void writeAtomically(Path target, String content) throws IOException {
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.writeString(tmp, content);
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}