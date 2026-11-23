package ui.canvas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Layout file names resolve through the shared name-validation family - no traversal. */
class LayoutStoreTest {

	@TempDir
	Path workDir;

	@Test
	void traversalNamesAreDeadEnds() {
		LayoutStore store = new LayoutStore(workDir.resolve("layout"));
		assertThrows(IllegalArgumentException.class, () -> store.move("../evil", "n1", 1, 2),
				"dot-dot flow names are rejected before any file call");
		assertThrows(IllegalArgumentException.class, () -> store.move("sub/escape", "n1", 1, 2),
				"separator names are rejected");
		assertThrows(IllegalArgumentException.class, () -> store.positions(".."),
				"dot segments are rejected");

		store.move("gradrate-ols", "n1", 10, 20);
		assertEquals(new LayoutStore.Pos(10, 20), store.positions("gradrate-ols").get("n1"));
		assertTrue(workDir.resolve("layout").resolve("gradrate-ols.layout.json").toFile().exists(),
				"legitimate names still land in the layout dir");
	}
}
