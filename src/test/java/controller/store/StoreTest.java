package controller.store;

import model.Book;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class StoreTest {

    @TempDir
    Path tempDir;

    static Stream<Function<String, Store>> stores() {
        return Stream.of(
                DatalakeLocalStoreTimeHierarchy::new,
                DatalakeLocalStoreBookHierarchy::new,
                DatalakeLocalStoreIdRangeHierarchy::new
        );
    }

    @ParameterizedTest
    @MethodSource("stores")
    void storeDataReturnsPathOfWrittenBody(Function<String, Store> factory) throws Exception {
        Store store = factory.apply(tempDir.toString());

        Path bodyPath = store.storeData(new Book(1342, "Title: Pride and Prejudice", "It is a truth..."));

        assertEquals("1342.body.txt", bodyPath.getFileName().toString());
        assertEquals("It is a truth...", Files.readString(bodyPath));
    }
}
