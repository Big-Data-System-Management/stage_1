import gzip
import unittest

from stage1 import gutenberg
from stage1.model import RawBook

START_MARKERS = [
    "*** START OF THE PROJECT GUTENBERG EBOOK PI ***",
    "*** START OF THIS PROJECT GUTENBERG EBOOK PI ***",
    "***START OF THE PROJECT GUTENBERG EBOOK PI***",
    "*** Start of the Project Gutenberg eBook Pi ***",
    "*** START OF THIS PROJECT GUTENBERG E-BOOK PI ***",
]


class GutenbergTest(unittest.TestCase):

    def test_accepts_the_start_marker_variants(self):
        for marker in START_MARKERS:
            with self.subTest(marker=marker):
                text = f"Title: Pi\r\n\r\n{marker}\r\n\r\n3.14159\r\n\r\n*** END OF THIS PROJECT GUTENBERG EBOOK PI ***\r\nfooter"
                book = gutenberg.process(RawBook(1, text))
                self.assertEqual("Title: Pi", book.head)
                self.assertEqual("3.14159", book.body)

    def test_body_does_not_contain_the_rest_of_the_start_marker_line(self):
        text = ("Title: Alice\n*** START OF THE PROJECT GUTENBERG EBOOK ALICE'S ADVENTURES IN WONDERLAND ***\n"
                "Alice was beginning to get very tired\n"
                "*** END OF THE PROJECT GUTENBERG EBOOK ALICE'S ADVENTURES IN WONDERLAND ***\n")
        self.assertEqual("Alice was beginning to get very tired", gutenberg.process(RawBook(1, text)).body)

    def test_ignores_an_end_marker_before_the_start_marker(self):
        text = ("*** END OF THE PROJECT GUTENBERG EBOOK X ***\nheader\n"
                "*** START OF THE PROJECT GUTENBERG EBOOK X ***\nbody\n"
                "*** END OF THE PROJECT GUTENBERG EBOOK X ***\n")
        self.assertEqual("body", gutenberg.process(RawBook(1, text)).body)

    def test_skips_books_without_markers(self):
        self.assertIsNone(gutenberg.process(RawBook(1, "plain text without markers")))

    def test_decodes_plain_and_gzipped_content(self):
        text = "Ωμέγα café"
        self.assertEqual(text, gutenberg.decode(text.encode("utf-8")))
        self.assertEqual(text, gutenberg.decode(gzip.compress(text.encode("utf-8"))))


if __name__ == "__main__":
    unittest.main()
