package core.persist;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * The relative-path gate for caller-supplied dataset paths: traversal and absolute paths
 * are refused before any file system call; plain and nested relative paths resolve inside
 * the workspace root.
 */
class NamesTest {

	private final Path base = Path.of("/tmp/workspace-root");

	@Test
	void traversalIsRefused() {
		assertThrows(IllegalArgumentException.class, () -> Names.safeRelative(base, "../secret.csv"));
		assertThrows(IllegalArgumentException.class, () -> Names.safeRelative(base, "a/../../b.csv"));
		assertThrows(IllegalArgumentException.class, () -> Names.safeRelative(base, ".."));
	}

	@Test
	void absolutePathsAreRefused() {
		assertThrows(IllegalArgumentException.class, () -> Names.safeRelative(base, "C:\\data\\x.csv"));
		assertThrows(IllegalArgumentException.class, () -> Names.safeRelative(base, "/etc/passwd"));
	}

	@Test
	void blankPathsAreRefused() {
		assertThrows(IllegalArgumentException.class, () -> Names.safeRelative(base, " "));
		assertThrows(IllegalArgumentException.class, () -> Names.safeRelative(base, null));
	}

	@Test
	void plainAndNestedRelativePathsResolveInside() {
		Path root = base.toAbsolutePath().normalize();
		assertEquals(root.resolve("data.csv"), Names.safeRelative(base, "data.csv"));
		assertEquals(root.resolve("sub").resolve("data.csv"), Names.safeRelative(base, "sub/data.csv"));
		assertDoesNotThrow(() -> Names.safeRelative(base, "runs/run-1.jsonl"));
	}
}
