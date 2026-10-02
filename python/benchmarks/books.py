import time

from stage1 import gutenberg
from stage1.files import REPO_ROOT, read_text, write_text
from stage1.model import RawBook

RAW_BOOKS_DIR = REPO_ROOT / "benchmark" / "raw"
MAX_BOOK_ID_TO_TRY = 1000
DOWNLOAD_DELAY_SECONDS = 1.0


def load(count, max_book_id=MAX_BOOK_ID_TO_TRY):
    books = []
    for book_id in range(1, max_book_id + 1):
        if len(books) >= count:
            break
        raw = raw_book(book_id)
        book = gutenberg.process(raw) if raw is not None else None
        if book is not None:
            books.append(book)
    if len(books) < count:
        raise RuntimeError(f"Solo hay {len(books)} libros válidos, se necesitan {count}")
    return books


def raw_book(book_id):
    RAW_BOOKS_DIR.mkdir(parents=True, exist_ok=True)
    cached = RAW_BOOKS_DIR / f"pg{book_id}.txt"
    missing = RAW_BOOKS_DIR / f"pg{book_id}.missing"
    if cached.exists():
        return RawBook(book_id, read_text(cached))
    if missing.exists():
        return None
    time.sleep(DOWNLOAD_DELAY_SECONDS)
    try:
        text = gutenberg.download_book(book_id)
    except OSError as error:
        if "404" in str(error):
            missing.touch()
        return None
    if not text:
        return None
    write_text(cached, text)
    return RawBook(book_id, text)
