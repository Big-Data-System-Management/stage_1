import os
import sys
from datetime import datetime
from pathlib import Path

from stage1.files import read_text, write_atomically
from stage1.model import Book

BODY_SUFFIX = ".body.txt"


def _walk_bodies(base_path):
    for directory, _, files in os.walk(base_path):
        for name in files:
            if not name.endswith(BODY_SUFFIX):
                continue
            try:
                yield int(name.replace(BODY_SUFFIX, "")), Path(directory)
            except ValueError:
                pass


def _read_book(book_id, directory):
    try:
        header = read_text(directory / f"{book_id}.header.txt")
        body = read_text(directory / f"{book_id}{BODY_SUFFIX}")
        return Book(book_id, header, body)
    except OSError as error:
        print(f"Error leyendo libro {book_id}: {error}", file=sys.stderr)
        return None


def _write_book(directory, book):
    directory.mkdir(parents=True, exist_ok=True)
    body_path = directory / f"{book.id}{BODY_SUFFIX}"
    write_atomically(directory / f"{book.id}.header.txt", book.head)
    write_atomically(body_path, book.body)
    return body_path


class DatalakeLocalStoreBookHierarchy:

    def __init__(self, base_data_lake_path):
        self.base_data_lake_path = Path(base_data_lake_path)
        self.existing_book_ids = set()
        if self.base_data_lake_path.exists():
            self.existing_book_ids.update(book_id for book_id, _ in _walk_bodies(self.base_data_lake_path))
            print(f"[Store Book-Hierarchy] Índice cargado en RAM: {len(self.existing_book_ids)} libros detectados.")

    def exists(self, book_id):
        return book_id in self.existing_book_ids

    def get_book(self, book_id):
        if not self.exists(book_id):
            return None
        return _read_book(book_id, self.base_data_lake_path / str(book_id))

    def store_data(self, book):
        body_path = _write_book(self.base_data_lake_path / str(book.id), book)
        self.existing_book_ids.add(book.id)
        return body_path


class DatalakeLocalStoreIdRangeHierarchy:

    BATCH_SIZE = 1000

    def __init__(self, base_data_lake_path):
        self.base_data_lake_path = Path(base_data_lake_path)
        self.existing_book_ids = set()
        if self.base_data_lake_path.exists():
            self.existing_book_ids.update(book_id for book_id, _ in _walk_bodies(self.base_data_lake_path))
            print(f"[Store Batch-Hierarchy] Índice cargado en RAM: {len(self.existing_book_ids)} libros detectados.")

    def exists(self, book_id):
        return book_id in self.existing_book_ids

    def get_book(self, book_id):
        if not self.exists(book_id):
            return None
        return _read_book(book_id, self._batch_directory(book_id))

    def store_data(self, book):
        body_path = _write_book(self._batch_directory(book.id), book)
        self.existing_book_ids.add(book.id)
        return body_path

    def _batch_directory(self, book_id):
        batch_number = book_id // self.BATCH_SIZE
        first = batch_number * self.BATCH_SIZE
        last = (batch_number + 1) * self.BATCH_SIZE - 1
        return self.base_data_lake_path / f"batch_{first}_to_{last}"


class DatalakeLocalStoreTimeHierarchy:

    def __init__(self, base_data_lake_path):
        self.base_data_lake_path = Path(base_data_lake_path)
        self.book_directories = {}
        if self.base_data_lake_path.exists():
            self.book_directories.update(_walk_bodies(self.base_data_lake_path))
            print(f"[Store] Índice cargado en RAM: {len(self.book_directories)} libros detectados.")

    def exists(self, book_id):
        return book_id in self.book_directories

    def get_book(self, book_id):
        directory = self.book_directories.get(book_id)
        if directory is None:
            return None
        return _read_book(book_id, directory)

    def store_data(self, book):
        now = datetime.now()
        directory = self.base_data_lake_path / now.strftime("%Y%m%d") / now.strftime("%H")
        body_path = _write_book(directory, book)
        self.book_directories[book.id] = directory
        return body_path


class CompositeStore:

    def __init__(self, stores):
        self.stores = list(stores)

    def store_data(self, book):
        for store in self.stores:
            store.store_data(book)
        return Path("")

    def exists(self, book_id):
        return all(store.exists(book_id) for store in self.stores)

    def get_book(self, book_id):
        for store in self.stores:
            if store.exists(book_id):
                book = store.get_book(book_id)
                if book is not None:
                    return book
        return None
