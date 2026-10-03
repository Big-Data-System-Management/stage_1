import os
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]

JAVA_WHITESPACE = (
    "\t\n\x0b\x0c\r\x1c\x1d\x1e\x1f "
    "           "
    "   　"
)


def java_strip(text):
    return text.strip(JAVA_WHITESPACE)


def read_text(path):
    return Path(path).read_bytes().decode("utf-8")


def write_text(path, content):
    Path(path).write_bytes(content.encode("utf-8"))


def write_atomically(target, content):
    target = Path(target)
    tmp = target.with_name(target.name + ".tmp")
    write_text(tmp, content)
    os.replace(tmp, target)
