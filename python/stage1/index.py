import json
from pathlib import Path

from pymongo import ASCENDING, MongoClient, UpdateOne

from stage1.files import java_strip, read_text, write_atomically

WINDOWS_RESERVED_NAMES = frozenset(
    ["con", "prn", "aux", "nul"]
    + [f"com{i}" for i in range(1, 10)]
    + [f"lpt{i}" for i in range(1, 10)]
)


def utf16_order(term):
    return term.encode("utf-16-be")


def _add_postings(postings, tokenizer, book_id, body):
    for term in set(tokenizer.tokenize(body)):
        books = postings.get(term)
        if books is None:
            postings[term] = {book_id}
        else:
            books.add(book_id)


def _single_term(tokenizer, term):
    tokens = tokenizer.tokenize(term)
    return tokens[0] if len(tokens) == 1 else None


class MonolithicJsonIndex:

    def __init__(self, index_file, tokenizer):
        self.index_file = Path(index_file)
        self.tokenizer = tokenizer
        self.postings = self._load_existing_index()

    def index_book(self, book_id, body):
        _add_postings(self.postings, self.tokenizer, book_id, body)

    def search(self, term):
        normalized_term = _single_term(self.tokenizer, term)
        if normalized_term is None:
            return []
        return sorted(self.postings.get(normalized_term, ()))

    def flush(self):
        self.index_file.absolute().parent.mkdir(parents=True, exist_ok=True)
        entries = (
            json.dumps(term, ensure_ascii=False) + ":[" + ",".join(map(str, sorted(self.postings[term]))) + "]"
            for term in sorted(self.postings, key=utf16_order)
        )
        write_atomically(self.index_file, "{" + ",".join(entries) + "}")

    def _load_existing_index(self):
        if not self.index_file.exists():
            return {}
        loaded = json.loads(read_text(self.index_file))
        return {term: set(books) for term, books in (loaded or {}).items()}


class HierarchicalFolderIndex:

    def __init__(self, root_directory, tokenizer):
        self.root_directory = Path(root_directory)
        self.tokenizer = tokenizer
        self.pending_postings = {}

    def index_book(self, book_id, body):
        _add_postings(self.pending_postings, self.tokenizer, book_id, body)

    def search(self, term):
        normalized_term = _single_term(self.tokenizer, term)
        if normalized_term is None:
            return []
        books = _read_postings(self._term_file(normalized_term))
        books |= self.pending_postings.get(normalized_term, set())
        return sorted(books)

    def flush(self):
        for term, book_ids in self.pending_postings.items():
            _merge_into_file(self._term_file(term), book_ids)
        self.pending_postings.clear()

    def _term_file(self, term):
        return self.root_directory / _folder_name(term) / _file_name(term)


def _folder_name(term):
    return term[0].upper()


def _file_name(term):
    name = "_" + term if term in WINDOWS_RESERVED_NAMES else term
    return name + ".txt"


def _merge_into_file(file, new_book_ids):
    books = _read_postings(file)
    if new_book_ids <= books:
        return
    books |= new_book_ids
    file.absolute().parent.mkdir(parents=True, exist_ok=True)
    write_atomically(file, "".join(f"{book}\n" for book in sorted(books)))


def _read_postings(file):
    try:
        content = read_text(file)
    except FileNotFoundError:
        return set()
    lines = (java_strip(line) for line in content.splitlines())
    return {int(line) for line in lines if line}


class MongoInvertedIndex:

    TERM_FIELD = "term"
    POSTINGS_FIELD = "postings"

    def __init__(self, connection_uri, database_name, collection_name, tokenizer):
        self.client = MongoClient(connection_uri)
        self.collection = self.client[database_name][collection_name]
        self.tokenizer = tokenizer
        self.pending_postings = {}
        self.collection.create_index([(self.TERM_FIELD, ASCENDING)], unique=True)

    def index_book(self, book_id, body):
        _add_postings(self.pending_postings, self.tokenizer, book_id, body)

    def search(self, term):
        normalized_term = _single_term(self.tokenizer, term)
        if normalized_term is None:
            return []
        books = self._read_postings(normalized_term)
        books |= self.pending_postings.get(normalized_term, set())
        return sorted(books)

    def flush(self):
        if not self.pending_postings:
            return
        updates = [self._to_upsert(term, book_ids) for term, book_ids in self.pending_postings.items()]
        self.collection.bulk_write(updates, ordered=False)
        self.pending_postings.clear()

    def close(self):
        self.client.close()

    def _to_upsert(self, term, book_ids):
        return UpdateOne(
            {self.TERM_FIELD: term},
            {"$addToSet": {self.POSTINGS_FIELD: {"$each": sorted(book_ids)}}},
            upsert=True,
        )

    def _read_postings(self, term):
        document = self.collection.find_one({self.TERM_FIELD: term})
        if document is None:
            return set()
        return set(document[self.POSTINGS_FIELD])
