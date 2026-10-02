from dataclasses import dataclass
from pathlib import Path


@dataclass(frozen=True)
class RawBook:
    book_id: int
    body: str


@dataclass(frozen=True)
class Book:
    id: int
    head: str
    body: str


@dataclass(frozen=True)
class BookMetadata:
    book_id: int
    title: str
    author: str
    language: str
    body_path: Path | None
