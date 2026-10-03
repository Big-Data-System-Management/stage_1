import shutil
import sys

from pymongo import MongoClient

from benchmarks import harness
from stage1.index import HierarchicalFolderIndex, MongoInvertedIndex, MonolithicJsonIndex

MONGO_URI = "mongodb://localhost:27017/?serverSelectionTimeoutMS=1000"
MONGO_DATABASE = "index_benchmark"
MONGO_COLLECTION = "inverted_index"
STRUCTURES = ["JSON", "FOLDER", "MONGO"]


def create(structure, work_dir, tokenizer):
    if structure == "JSON":
        return MonolithicJsonIndex(work_dir / "inverted_index.json", tokenizer)
    if structure == "FOLDER":
        return HierarchicalFolderIndex(work_dir / "inverted_index", tokenizer)
    if structure == "MONGO":
        return MongoInvertedIndex(MONGO_URI, MONGO_DATABASE, MONGO_COLLECTION, tokenizer)
    raise ValueError(f"Estructura no soportada: {structure}")


def is_mongo_available():
    try:
        with MongoClient(MONGO_URI) as client:
            client.admin.command("ping")
        return True
    except Exception:
        return False


def available_structures():
    return [structure for structure in STRUCTURES if structure != "MONGO" or is_mongo_available()]


def reset_mongo(structure):
    if structure != "MONGO":
        return
    if not is_mongo_available():
        raise RuntimeError("MongoDB no está arrancado en localhost:27017")
    drop_mongo_database()


def dispose(structure, index, work_dir):
    if index is not None and hasattr(index, "close"):
        index.close()
    if work_dir.exists():
        shutil.rmtree(work_dir)
    if structure == "MONGO":
        drop_mongo_database()


def run(state_class):
    options = harness.parse_options(sys.argv[1:])
    structures = available_structures()
    if len(structures) < len(STRUCTURES):
        print("[BENCHMARK] MongoDB no está arrancado: se omite la estructura MONGO.")
    harness.run(state_class, {**state_class.params, "structure": structures}, options)


def drop_mongo_database():
    with MongoClient(MONGO_URI) as client:
        client.drop_database(MONGO_DATABASE)
