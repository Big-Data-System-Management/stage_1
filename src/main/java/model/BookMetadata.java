package model;

import java.nio.file.Path;

public record BookMetadata(int bookId, String title, String author, String language, Path bodyPath) {
}
