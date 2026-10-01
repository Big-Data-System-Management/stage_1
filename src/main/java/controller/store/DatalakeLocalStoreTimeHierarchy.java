package controller.store;

import model.Book;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

public class DatalakeLocalStoreTimeHierarchy implements Store {

    private final String baseDataLakePath;
    private final Map<Integer, Path> bookDirectories = new ConcurrentHashMap<>();

    public DatalakeLocalStoreTimeHierarchy(String baseDataLakePath) {
        this.baseDataLakePath = baseDataLakePath;
        this.indexExistingBooks();
    }

    private void indexExistingBooks() {
        Path basePath = Paths.get(this.baseDataLakePath);
        if (!Files.exists(basePath)) return;
        try (Stream<Path> stream = Files.walk(basePath)) {
            extractAndAddExistingIds(stream);
            System.out.printf("[Store Time-Hierarchy] Índice cargado en RAM: %d libros detectados.%n", bookDirectories.size());
        } catch (IOException e) {
            System.err.println("Error indexando Data Lake: " + e.getMessage());
        }
    }

    private void extractAndAddExistingIds(Stream<Path> stream) {
        stream.filter(Files::isRegularFile)
                .filter(path -> path.getFileName().toString().endsWith(".body.txt"))
                .forEach(path -> {
                    try {
                        String fileName = path.getFileName().toString();
                        int id = Integer.parseInt(fileName.replace(".body.txt", ""));
                        bookDirectories.put(id, path.getParent());
                    } catch (NumberFormatException ignored) {}
                });
    }

    @Override
    public boolean exists(int bookId) {
        return bookDirectories.containsKey(bookId);
    }

    @Override
    public Book getBook(int id) {
        Path targetDir = bookDirectories.get(id); // Búsqueda instantánea O(1)
        if (targetDir == null) return null;

        Path headerPath = targetDir.resolve(id + ".header.txt");
        Path bodyPath = targetDir.resolve(id + ".body.txt");

        try {
            String header = Files.readString(headerPath);
            String body = Files.readString(bodyPath);
            return new Book(id, header, body);
        } catch (IOException e) {
            System.err.printf("Error leyendo libro %d: %s%n", id, e.getMessage());
            return null;
        }
    }

    @Override
    public void storeData(Book book) throws IOException {
        LocalDateTime now = LocalDateTime.now();
        String dateFolder = now.format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        String hourFolder = now.format(DateTimeFormatter.ofPattern("HH"));

        Path targetDir = Paths.get(this.baseDataLakePath, dateFolder, hourFolder);
        Files.createDirectories(targetDir);

        Path headerPath = targetDir.resolve(book.id() + ".header.txt");
        Path bodyPath = targetDir.resolve(book.id() + ".body.txt");

        writeAtomically(headerPath, book.head());
        writeAtomically(bodyPath, book.body());

        bookDirectories.put(book.id(), targetDir);
    }

    private static void writeAtomically(Path target, String content) throws IOException {
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.writeString(tmp, content);
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}