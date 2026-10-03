import tempfile
import unittest
from pathlib import Path

from stage1.files import read_text
from stage1.index import HierarchicalFolderIndex, MonolithicJsonIndex
from stage1.tokenizer import Tokenizer


class IndexTest(unittest.TestCase):

    tokenizer = Tokenizer()

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.temp_dir = Path(self.temp.name)

    def tearDown(self):
        self.temp.cleanup()

    def test_monolithic_index_writes_sorted_compact_json(self):
        file = self.temp_dir / "datamarts" / "inverted_index.json"
        index = MonolithicJsonIndex(file, self.tokenizer)
        index.index_book(12, "island adventure")
        index.index_book(5, "adventure")
        index.flush()
        self.assertEqual('{"adventure":[5,12],"island":[12]}', read_text(file))
        self.assertFalse(file.with_name("inverted_index.json.tmp").exists())

    def test_monolithic_index_reloads_and_extends_the_existing_file(self):
        file = self.temp_dir / "inverted_index.json"
        first = MonolithicJsonIndex(file, self.tokenizer)
        first.index_book(5, "whale")
        first.flush()
        second = MonolithicJsonIndex(file, self.tokenizer)
        second.index_book(2701, "Whale")
        self.assertEqual([5, 2701], second.search("WHALE"))

    def test_folder_index_writes_one_file_per_term_grouped_by_first_letter(self):
        root = self.temp_dir / "inverted_index"
        index = HierarchicalFolderIndex(root, self.tokenizer)
        index.index_book(12, "adventure 1813")
        index.index_book(5, "adventure")
        index.flush()
        self.assertEqual("5\n12\n", read_text(root / "A" / "adventure.txt"))
        self.assertEqual("12\n", read_text(root / "1" / "1813.txt"))

    def test_folder_index_prefixes_windows_reserved_names(self):
        root = self.temp_dir / "inverted_index"
        index = HierarchicalFolderIndex(root, self.tokenizer)
        index.index_book(1, "café con leche")
        index.flush()
        self.assertTrue((root / "C" / "_con.txt").exists())
        self.assertTrue((root / "C" / "cafe.txt").exists())
        self.assertEqual([1], index.search("con"))

    def test_search_returns_nothing_for_several_terms_or_stopwords(self):
        index = MonolithicJsonIndex(self.temp_dir / "inverted_index.json", self.tokenizer)
        index.index_book(1, "whale island")
        self.assertEqual([], index.search("whale island"))
        self.assertEqual([], index.search("the"))


if __name__ == "__main__":
    unittest.main()
