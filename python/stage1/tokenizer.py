import re
import sys
import unicodedata

from stage1.files import REPO_ROOT, java_strip, read_text

MIN_TOKEN_LENGTH = 2
MAX_TOKEN_LENGTH = 50
STOPWORDS_FILE = REPO_ROOT / "src" / "main" / "resources" / "stopwords.txt"
TOKEN = re.compile(r"[^\W_]+")
COMBINING_MARKS = {
    code_point: None
    for code_point in range(sys.maxunicode + 1)
    if unicodedata.category(chr(code_point)).startswith("M")
}


class Tokenizer:

    def __init__(self, stopwords=None):
        self.stopwords = frozenset(load_default_stopwords() if stopwords is None else stopwords)

    def tokenize(self, text):
        if text is None:
            return []
        stopwords = self.stopwords
        return [
            token for token in TOKEN.findall(normalize(text))
            if MIN_TOKEN_LENGTH <= len(token) <= MAX_TOKEN_LENGTH and token not in stopwords
        ]


def normalize(text):
    return unicodedata.normalize("NFD", text).translate(COMBINING_MARKS).lower().replace("ς", "σ")


def load_default_stopwords():
    lines = (java_strip(line) for line in read_text(STOPWORDS_FILE).splitlines())
    return {line for line in lines if line}
