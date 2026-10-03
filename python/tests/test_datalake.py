import tempfile
import unittest
from pathlib import Path

from stage1.datalake import (
    DatalakeLocalStoreBookHierarchy,
    DatalakeLocalStoreIdRangeHierarchy,
    DatalakeLocalStoreTimeHierarchy,
)
from stage1.files import read_text
from stage1.model import Book

STORES = [DatalakeLocalStoreBookHierarchy, DatalakeLocalStoreIdRangeHierarchy, DatalakeLocalStoreTimeHierarchy]


class DatalakeTest(unittest.TestCase):

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)

    def tearDown(self):
        self.temp.cleanup()

    def test_stores_reads_and_recovers_books(self):
        for store_class in STORES:
            with self.subTest(store=store_class.__name__):
                base = self.root / store_class.__name__
                book = Book(1342, "Title: Pride\r\n", "It is a truth\r\nuniversally acknowledged")
                body_path = store_class(base).store_data(book)
                self.assertEqual(book.body, read_text(body_path))
                reopened = store_class(base)
                self.assertTrue(reopened.exists(1342))
                self.assertFalse(reopened.exists(1))
                self.assertEqual(book, reopened.get_book(1342))

    def test_layouts(self):
        book = Book(1342, "h", "b")
        self.assertEqual(Path("1342", "1342.body.txt"),
                         DatalakeLocalStoreBookHierarchy(self.root).store_data(book).relative_to(self.root))
        self.assertEqual(Path("batch_1000_to_1999", "1342.body.txt"),
                         DatalakeLocalStoreIdRangeHierarchy(self.root).store_data(book).relative_to(self.root))

    def test_ignores_books_without_body(self):
        (self.root / "5").mkdir()
        (self.root / "5" / "5.header.txt").write_text("h")
        (self.root / "5" / "5.body.txt.tmp").write_text("b")
        self.assertFalse(DatalakeLocalStoreBookHierarchy(self.root).exists(5))


if __name__ == "__main__":
    unittest.main()
