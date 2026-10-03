import gzip
import re
import sys
import time
import urllib.error
import urllib.request

from stage1.files import java_strip
from stage1.model import Book

URL_PATTERN = "https://www.gutenberg.org/cache/epub/{0}/pg{0}.txt"
HEADERS = {
    "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36",
    "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
    "Accept-Language": "es-ES,es;q=0.9,en-US;q=0.8,en;q=0.7",
    "Cache-Control": "max-age=0",
    "Upgrade-Insecure-Requests": "1",
    "Sec-Ch-Ua": "\"Chromium\";v=\"122\", \"Not(A:Brand\";v=\"24\", \"Google Chrome\";v=\"122\"",
    "Sec-Ch-Ua-Mobile": "?0",
    "Sec-Ch-Ua-Platform": "\"Windows\"",
    "Sec-Fetch-Dest": "document",
    "Sec-Fetch-Mode": "navigate",
    "Sec-Fetch-Site": "none",
    "Sec-Fetch-User": "?1",
}

START_MARKER = re.compile(r"\*\*\* ?START OF (THE|THIS) PROJECT GUTENBERG E-?BOOK[^\r\n]*", re.IGNORECASE | re.ASCII)
END_MARKER = re.compile(r"\*\*\* ?END OF (THE|THIS) PROJECT GUTENBERG E-?BOOK", re.IGNORECASE | re.ASCII)


def decode(content):
    if content[:2] == b"\x1f\x8b":
        content = gzip.decompress(content)
    return content.decode("utf-8", errors="replace")


def download_book(book_id):
    request = urllib.request.Request(URL_PATTERN.format(book_id), headers=HEADERS)
    try:
        with urllib.request.urlopen(request, timeout=10) as response:
            return decode(response.read())
    except urllib.error.HTTPError as error:
        _handle_status(error.code, book_id)


def _handle_status(status_code, book_id):
    if status_code == 403:
        print(f"CRITICAL ALERT HTTP 403 for ID {book_id}! Access denied/possible ban. Pausing 2 minutes...", file=sys.stderr)
        time.sleep(120)
        raise OSError("Access forbidden (HTTP 403)")
    if status_code == 429:
        print(f"ALERT HTTP 429 for ID {book_id}! Server overloaded. Pausing 1 minute...", file=sys.stderr)
        time.sleep(60)
        raise OSError("Too many requests (HTTP 429)")
    if 500 <= status_code < 600:
        print(f"Server error HTTP {status_code} for ID {book_id}. Pausing 10 seconds...", file=sys.stderr)
        time.sleep(10)
        raise OSError(f"Internal server error (HTTP {status_code})")
    raise OSError(f"Unclassified HTTP error: {status_code}")


def process(raw_book):
    if raw_book is None or raw_book.body is None:
        print("Invalid or empty RawBook.", file=sys.stderr)
        return None
    text = raw_book.body
    start = START_MARKER.search(text)
    end = END_MARKER.search(text, start.end()) if start else None
    if end is None:
        print(f"Project Gutenberg markers not found for book ID: {raw_book.book_id}", file=sys.stderr)
        return None
    header = java_strip(text[:start.start()])
    body = java_strip(text[start.end():end.start()])
    return Book(raw_book.book_id, header, body)
