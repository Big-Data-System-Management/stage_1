package benchmarks.datalake;

import java.nio.file.Path;

public final class BenchmarkPaths {

    private BenchmarkPaths() {}


    public static final String PATH_TIME_HIERARCHY = "datalakeTimeHierarchy";
    public static final String PATH_BOOK_HIERARCHY = "datalakeBookHierarchy";
    public static final String PATH_ID_RANGE_HIERARCHY = "datalakeIdRangeHierarchy";

    public static String getPathForStrategy(String strategy) {
        return switch (strategy) {
            case "TIME_HIERARCHY" -> PATH_TIME_HIERARCHY;
            case "BOOK_HIERARCHY" -> PATH_BOOK_HIERARCHY;
            case "ID_RANGE_HIERARCHY" -> PATH_ID_RANGE_HIERARCHY;
            default -> throw new IllegalArgumentException("Estrategia no soportada: " + strategy);
        };
    }

    public static Path getPathObjectForStrategy(String strategy) {
        return Path.of(getPathForStrategy(strategy));
    }
}