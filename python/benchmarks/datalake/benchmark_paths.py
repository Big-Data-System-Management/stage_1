from stage1.datalake import (
    DatalakeLocalStoreBookHierarchy,
    DatalakeLocalStoreIdRangeHierarchy,
    DatalakeLocalStoreTimeHierarchy,
)
from stage1.files import REPO_ROOT

STRATEGIES = ["TIME_HIERARCHY", "BOOK_HIERARCHY", "ID_RANGE_HIERARCHY"]
PATHS = {
    "TIME_HIERARCHY": REPO_ROOT / "datalakeTimeHierarchy",
    "BOOK_HIERARCHY": REPO_ROOT / "datalakeBookHierarchy",
    "ID_RANGE_HIERARCHY": REPO_ROOT / "datalakeIdRangeHierarchy",
}
STORES = {
    "TIME_HIERARCHY": DatalakeLocalStoreTimeHierarchy,
    "BOOK_HIERARCHY": DatalakeLocalStoreBookHierarchy,
    "ID_RANGE_HIERARCHY": DatalakeLocalStoreIdRangeHierarchy,
}


def path_for_strategy(strategy):
    if strategy not in PATHS:
        raise ValueError(f"Unsupported strategy: {strategy}")
    return PATHS[strategy]


def create_store(strategy, path):
    if strategy not in STORES:
        raise ValueError(f"Unknown strategy: {strategy}")
    return STORES[strategy](path)
