package controller.datamart;

import model.BookMetadata;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class SqliteMetadataRepository implements MetadataRepository {

    private static final String CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS books (
                book_id   INTEGER PRIMARY KEY,
                title     TEXT COLLATE NOCASE,
                author    TEXT COLLATE NOCASE,
                language  TEXT COLLATE NOCASE,
                body_path TEXT
            )""";
    private static final String CREATE_AUTHOR_INDEX = "CREATE INDEX IF NOT EXISTS idx_books_author ON books(author)";
    private static final String CREATE_TITLE_INDEX = "CREATE INDEX IF NOT EXISTS idx_books_title ON books(title)";
    private static final String CREATE_LANGUAGE_INDEX = "CREATE INDEX IF NOT EXISTS idx_books_language ON books(language)";

    private static final String UPSERT = """
            INSERT INTO books (book_id, title, author, language, body_path) VALUES (?, ?, ?, ?, ?)
            ON CONFLICT(book_id) DO UPDATE SET
                title = excluded.title,
                author = excluded.author,
                language = excluded.language,
                body_path = excluded.body_path""";
    private static final String SELECT = "SELECT book_id, title, author, language, body_path FROM books";

    private final Connection connection;

    public SqliteMetadataRepository(Path databaseFile) throws IOException, SQLException {
        Path parent = databaseFile.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        this.connection = DriverManager.getConnection("jdbc:sqlite:" + databaseFile);
        createSchema();
    }

    private void createSchema() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(CREATE_TABLE);
            statement.execute(CREATE_AUTHOR_INDEX);
            statement.execute(CREATE_TITLE_INDEX);
            statement.execute(CREATE_LANGUAGE_INDEX);
        }
    }

    @Override
    public void save(BookMetadata metadata) {
        saveAll(List.of(metadata));
    }

    @Override
    public synchronized void saveAll(List<BookMetadata> metadata) {
        try {
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement(UPSERT)) {
                for (BookMetadata book : metadata) {
                    bind(statement, book);
                    statement.addBatch();
                }
                statement.executeBatch();
                connection.commit();
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new MetadataRepositoryException("Error saving book metadata", e);
        }
    }

    private static void bind(PreparedStatement statement, BookMetadata book) throws SQLException {
        statement.setInt(1, book.bookId());
        statement.setString(2, book.title());
        statement.setString(3, book.author());
        statement.setString(4, book.language());
        statement.setString(5, book.bodyPath() == null ? null : book.bodyPath().toString());
    }

    @Override
    public Optional<BookMetadata> findById(int bookId) {
        return query(SELECT + " WHERE book_id = ?", bookId).stream().findFirst();
    }

    @Override
    public List<BookMetadata> findByAuthor(String author) {
        return query(SELECT + " WHERE author = ? ORDER BY book_id", author);
    }

    @Override
    public List<BookMetadata> findByTitle(String title) {
        return query(SELECT + " WHERE title = ? ORDER BY book_id", title);
    }

    @Override
    public List<BookMetadata> findByLanguage(String language) {
        return query(SELECT + " WHERE language = ? ORDER BY book_id", language);
    }

    @Override
    public List<BookMetadata> findAll() {
        return query(SELECT + " ORDER BY book_id");
    }

    @Override
    public synchronized int count() {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT COUNT(*) FROM books")) {
            return rs.getInt(1);
        } catch (SQLException e) {
            throw new MetadataRepositoryException("Error counting books", e);
        }
    }

    private synchronized List<BookMetadata> query(String sql, Object... params) {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                statement.setObject(i + 1, params[i]);
            }
            try (ResultSet rs = statement.executeQuery()) {
                List<BookMetadata> result = new ArrayList<>();
                while (rs.next()) result.add(map(rs));
                return result;
            }
        } catch (SQLException e) {
            throw new MetadataRepositoryException("Error querying book metadata", e);
        }
    }

    private static BookMetadata map(ResultSet rs) throws SQLException {
        String bodyPath = rs.getString("body_path");
        return new BookMetadata(
                rs.getInt("book_id"),
                rs.getString("title"),
                rs.getString("author"),
                rs.getString("language"),
                bodyPath == null ? null : Path.of(bodyPath)
        );
    }

    @Override
    public void close() throws SQLException {
        connection.close();
    }
}
