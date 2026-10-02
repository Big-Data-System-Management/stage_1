import tempfile
import unittest
from pathlib import Path

from stage1.metadata import MetadataExtractor, SqliteMetadataRepository
from stage1.model import Book


class MetadataTest(unittest.TestCase):

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.repository = SqliteMetadataRepository(Path(self.temp.name) / "metadata.db")

    def tearDown(self):
        self.repository.close()
        self.temp.cleanup()

    def test_extracts_fields_joining_continuation_lines_and_keeps_body_path(self):
        header = ("Title: Peter Pan\n"
                  "        [Peter and Wendy]\n"
                  "Editor: Eric S. Raymond\n"
                  "        Guy L. Steele\n"
                  "Language: English\n")
        body_path = Path(self.temp.name) / "16.body.txt"
        MetadataExtractor(self.repository).extract_and_process(Book(16, header, "body"), body_path)
        saved = self.repository.find_by_id(16)
        self.assertEqual("Peter Pan [Peter and Wendy]", saved.title)
        self.assertEqual("Eric S. Raymond; Guy L. Steele", saved.author)
        self.assertEqual("English", saved.language)
        self.assertEqual(body_path, saved.body_path)

    def test_uses_unknown_when_a_field_is_missing(self):
        MetadataExtractor(self.repository).extract_and_process(Book(7, "Title: The Mayflower Compact\r\n", "body"), None)
        saved = self.repository.find_by_id(7)
        self.assertEqual("The Mayflower Compact", saved.title)
        self.assertEqual("Unknown", saved.author)
        self.assertEqual("Unknown", saved.language)

    def test_upserts_and_queries_case_insensitively(self):
        extractor = MetadataExtractor(self.repository)
        extractor.extract_and_process(Book(1, "Title: Old\nAuthor: Jane Austen\n", ""), None)
        extractor.extract_and_process(Book(1, "Title: Emma\nAuthor: Jane Austen\n", ""), None)
        self.assertEqual(1, self.repository.count())
        self.assertEqual(["Emma"], [book.title for book in self.repository.find_by_author("jane austen")])


if __name__ == "__main__":
    unittest.main()
