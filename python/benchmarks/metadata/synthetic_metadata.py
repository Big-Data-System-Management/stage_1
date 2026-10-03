from pathlib import Path

from benchmarks.java_random import JavaRandom
from stage1.model import BookMetadata

SEED = 42
BOOKS_PER_AUTHOR = 20
LANGUAGES = ["English"] * 7 + ["French", "German", "Spanish"]


def generate(count):
    random = JavaRandom(SEED)
    authors = author_count(count)
    books = []
    for book_id in range(1, count + 1):
        author_name = author(random.next_int(authors))
        language = LANGUAGES[random.next_int(len(LANGUAGES))]
        body_path = Path("datalake", "20260101", f"{book_id % 24:02d}", f"{book_id}.body.txt")
        books.append(BookMetadata(book_id, title(book_id), author_name, language, body_path))
    return books


def author_count(book_count):
    return max(1, book_count // BOOKS_PER_AUTHOR)


def author(index):
    return f"Author {index}"


def title(book_id):
    return f"Title of book {book_id}"
