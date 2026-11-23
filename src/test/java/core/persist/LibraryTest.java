package core.persist;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import core.model.FlowDocument;
import core.model.Node;
import core.model.RequirementThread;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The library's machine legs: demo mutation refused, copy-into-project the only demo-derived
 * mutation, search over names and thread text, and the ruled version bump on re-confirm.
 */
class LibraryTest {

	@TempDir
	Path workDir;

	private Library library() throws Exception {
		return new Library(workDir.resolve("workspace"));
	}

	@Test
	void demoRootIsReadOnlyAndCopyIsTheOnlyMutation() throws Exception {
		Library lib = library();
		assertEquals(List.of("colon-svm", "gradrate-ols", "iris-knn"), lib.demos(), "the three archetypes ship");

		FlowStore demoStore = new FlowStore(lib.demoRoot());
		FlowDocument doc = lib.openDemo("iris-knn").document();
		assertThrows(IllegalStateException.class,
				() -> demoStore.save(doc, RequirementThread.fromMarkdown("# t\n\n"), lib.demoRoot().resolve("evil.zip")),
				"the demo root refuses writes");

		lib.createProject("thesis");
		FlowDocument copied = lib.copyDemo("iris-knn", "thesis");
		assertEquals("iris-knn", copied.name(), "the copy starts as the demo's content");
		assertEquals(1, copied.version(), "the copy starts at version 1");
		assertEquals(List.of("thesis"), lib.projects());
	}

	@Test
	void searchMatchesNamesAndThreadText() throws Exception {
		Library lib = library();
		assertTrue(lib.search("graduation").contains("demo:gradrate-ols"), "thread text is searchable");
		assertTrue(lib.search("iris").contains("demo:iris-knn"), "names are searchable");
		assertTrue(lib.search("colon").contains("demo:colon-svm"));

		lib.createProject("thesis");
		assertTrue(lib.search("thesis").contains("project:thesis"), "projects are searchable");

		// thread text rides along on the copy: the project's own thread is searchable too
		lib.copyDemo("gradrate-ols", "thesis");
		assertTrue(lib.search("graduation").contains("project:thesis"), "project thread text is searchable");
	}

	@Test
	void reconfirmWithChangedSummaryBumpsTheVersion() throws Exception {
		Library lib = library();
		lib.createProject("thesis");
		FlowDocument v1 = lib.copyDemo("iris-knn", "thesis");
		RequirementThread thread = lib.openDemo("iris-knn").thread();

		// re-confirm of the unchanged summary: no new version
		FlowDocument same = lib.saveVersion("thesis", v1, thread);
		assertEquals(List.of(1), lib.versions("thesis"), "an unchanged re-confirm does not bump");
		assertEquals(1, same.version());

		// a changed summary materializes a NEW immutable version and bumps document.version
		Node knn = v1.nodes().stream().filter(n -> n.type().equals("knn-classify")).findFirst().orElseThrow();
		Node tuned = new Node(knn.id(), knn.type(), java.util.Map.of("target", "species",
				"features", "sepal_len,sepal_wid,petal_len,petal_wid", "k", 7));
		FlowDocument edited = new FlowDocument(v1.name(), v1.version(),
				v1.nodes().stream().map(n -> n.id().equals(knn.id()) ? tuned : n).toList(),
				v1.wires(), v1.seed(), v1.provenance(), v1.threadId(), v1.confirmHistory());
		FlowDocument bumped = lib.saveVersion("thesis", edited, thread);
		assertEquals(List.of(1, 2), lib.versions("thesis"), "the changed summary materializes version 2");
		assertEquals(2, bumped.version(), "document.version bumps with the new version");

		FlowDocument reloaded = lib.openVersion("thesis", 1);
		assertEquals(1, reloaded.version(), "the prior version stays immutable on disk");
		assertTrue(reloaded.contentHash().equals(v1.contentHash()), "version 1 bytes are the original summary");
	}

	@Test
	void traversalAndSeparatorNamesAreDeadEnds() throws Exception {
		Library lib = library();
		assertThrows(IllegalArgumentException.class, () -> lib.createProject("../library/demo"),
				"dot-dot project names are rejected before any file call");
		assertThrows(IllegalArgumentException.class, () -> lib.createProject("thesis/escape"),
				"separator names are rejected");
		assertThrows(IllegalArgumentException.class, () -> lib.createProject(".."),
				"dot segments are rejected");
		assertThrows(IllegalArgumentException.class, () -> lib.openDemo("../../secrets"),
				"demo lookups validate the name too");

		// the store-level guard: a path whose raw form starts elsewhere but normalizes INTO the
		// read-only demo root is the actual bypass shape - normalization must close it
		FlowStore.LoadedFlow loaded = lib.openDemo("iris-knn");
		FlowStore guarded = new FlowStore(lib.demoRoot());
		Path sneaky = workDir.resolve("elsewhere").resolve("..").resolve("workspace")
				.resolve("library").resolve("demo").resolve("evil.zip");
		assertThrows(IllegalStateException.class, () -> guarded.save(loaded.document(), loaded.thread(), sneaky),
				"a dotted path that normalizes into the demo root is refused");
		assertTrue(!Files.exists(sneaky.normalize()), "nothing was written");
	}
}
