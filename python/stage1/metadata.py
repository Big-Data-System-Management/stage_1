import re
import sqlite3
from pathlib import Path

from stage1.files import java_strip
from stage1.model import BookMetadata

UNKNOWN = "Unknown"
NOT_LINE_END = r"[^\n\r\x85  ]"
LINE_BREAK = r"(?:\r\n|[\n\x0b\x0c\r\x85  ])"
LINE_START = r"(?:^|(?<=[\r\x85  ]))"
CONTINUATION = re.compile(LINE_BREAK + r"[ \t]+")
BLANKS = re.compile(r"[ \t]+")


def _field(name):
    return re.compile(
        LINE_START + name + r":[ \t]*(" + NOT_LINE_END + r"+(?:" + LINE_BREAK + r"[ \t]+" + NOT_LINE_END + r"+)*)",
        re.MULTILINE,
    )


TITLE = _field("Title")
LANGUAGE = _field("Language")
AUTHOR_FIELDS = [_field("Author"), _field("Editor"), _field("Translator"), _field("Compiler")]


class MetadataExtractor:

    def __init__(self, repository):
        self.repository = repository

    def extract_and_process(self, book, body_path):
        header = book.head
        title = _find(TITLE, header, " ") or UNKNOWN
        author = _find_author(header) or UNKNOWN
        language = _find(LANGUAGE, header, " ") or UNKNOWN
        self.repository.save(BookMetadata(book.id, title, author, language, body_path))


def _find_author(header):
    for pattern in AUTHOR_FIELDS:
        value = _find(pattern, header, "; ")
        if value:
            return value
    return None


def _find(pattern, header, line_separator):
    match = pattern.search(header)
    if match is None:
        return None
    value = CONTINUATION.sub(lambda _: line_separator, match.group(1))
    value = java_strip(BLANKS.sub(" ", value))
    return value or None


CREATE_TABLE = """
CREATE TABLE IF NOT EXISTS books (
    book_id   INTEGER PRIMARY KEY,
    title     TEXT COLLATE NOCASE,
    author    TEXT COLLATE NOCASE,
    language  TEXT COLLATE NOCASE,
    body_path TEXT
)"""
CREATE_AUTHOR_INDEX = "CREATE INDEX IF NOT EXISTS idx_books_author ON books(author)"
CREATE_TITLE_INDEX = "CREATE INDEX IF NOT EXISTS idx_books_title ON books(title)"
CREATE_LANGUAGE_INDEX = "CREATE INDEX IF NOT EXISTS idx_books_language ON books(language)"
UPSERT = """
INSERT INTO books (book_id, title, author, language, body_path) VALUES (?, ?, ?, ?, ?)
ON CONFLICT(book_id) DO UPDATE SET
    title = excluded.title,
    author = excluded.author,
    language = excluded.language,
    body_path = excluded.body_path"""
SELECT = "SELECT book_id, title, author, language, body_path FROM books"


class SqliteMetadataRepository:

    def __init__(self, database_file):
        database_file = Path(database_file)
        database_file.absolute().parent.mkdir(parents=True, exist_ok=True)
        self.connection = sqlite3.connect(database_file, isolation_level=None)
        for statement in (CREATE_TABLE, CREATE_AUTHOR_INDEX, CREATE_TITLE_INDEX, CREATE_LANGUAGE_INDEX):
            self.connection.execute(statement)

    def save(self, metadata):
        self.save_all([metadata])

    def save_all(self, metadata):
        cursor = self.connection.cursor()
        cursor.execute("BEGIN")
        try:
            cursor.executemany(UPSERT, [_bind(book) for book in metadata])
            cursor.execute("COMMIT")
        except Exception:
            cursor.execute("ROLLBACK")
            raise

    def find_by_id(self, book_id):
        books = self._query(SELECT + " WHERE book_id = ?", book_id)
        return books[0] if books else None

    def find_by_author(self, author):
        return self._query(SELECT + " WHERE author = ? ORDER BY book_id", author)

    def find_by_title(self, title):
        return self._query(SELECT + " WHERE title = ? ORDER BY book_id", title)

    def find_by_language(self, language):
        return self._query(SELECT + " WHERE language = ? ORDER BY book_id", language)

    def find_all(self):
        return self._query(SELECT + " ORDER BY book_id")

    def count(self):
        return self.connection.execute("SELECT COUNT(*) FROM books").fetchone()[0]

    def close(self):
        self.connection.close()

    def _query(self, sql, *params):
        return [_map(row) for row in self.connection.execute(sql, params)]


def _bind(book):
    body_path = None if book.body_path is None else str(book.body_path)
    return book.book_id, book.title, book.author, book.language, body_path


def _map(row):
    book_id, title, author, language, body_path = row
    return BookMetadata(book_id, title, author, language, None if body_path is None else Path(body_path))
