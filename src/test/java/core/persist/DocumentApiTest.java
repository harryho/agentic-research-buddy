package core.persist;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import core.model.ConfirmHistoryEntry;
import core.model.FlowDocument;
import core.model.FlowVersion;
import core.model.Node;
import core.model.Provenance;
import core.model.RequirementThread;
import core.model.Wire;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DocumentApiTest {

	@TempDir
	Path workDir;

	private FlowDocument sampleDocument() {
		return new FlowDocument(
				"grad-rate-study",
				1,
				List.of(
						new Node("import-csv-1", "import-csv", java.util.Map.of("path", "students.csv")),
						new Node("prediction-1", "prediction-table", java.util.Map.of())),
				List.of(new Wire("import-csv-1", "prediction-1")),
				7L,
				new Provenance("hand-built", "harry", "2026-11-23T10:00:00Z"),
				"thread-1",
				List.of(new ConfirmHistoryEntry("confirm", "build", "harry", "card-1",
						"CONFIRMED_BUILD", "2026-11-23T10:05:00Z", null)));
	}

	private RequirementThread sampleThread() {
		return new RequirementThread("thread-1", List.of(
				new RequirementThread.Entry("harry", "Which factors predict graduation rate?",
						"2026-11-23T09:58:00Z"),
				new RequirementThread.Entry("buddy", "Proposed OLS over the students dataset.",
						"2026-11-23T10:01:00Z")));
	}

	@Test
	void jsonRoundTripIsByteStable() {
		FlowDocument doc = sampleDocument();
		String once = doc.toJson();
		String twice = FlowDocument.fromJson(once).toJson();
		assertEquals(once, twice);
	}

	@Test
	void zipPackageRoundTripKeepsManifestAndThreadBytesIdentical() throws Exception {
		FlowStore store = new FlowStore();
		FlowDocument doc = sampleDocument();
		RequirementThread thread = sampleThread();
		Path first = workDir.resolve("study.flow.zip");
		store.save(doc, thread, first);

		FlowStore.LoadedFlow loaded = store.load(first);
		assertEquals(doc.toJson(), loaded.document().toJson());
		assertEquals(thread, loaded.thread());

		Path second = workDir.resolve("study-again.flow.zip");
		store.save(loaded.document(), loaded.thread(), second);
		assertArrayEquals(store.manifestBytes(first), store.manifestBytes(second));
		assertArrayEquals(store.threadBytes(first), store.threadBytes(second));
		assertNotNull(loaded.document().threadId());
		assertEquals(doc.threadId(), loaded.thread().threadId());
	}

	@Test
	void versionSnapshotsAreImmutable() {
		FlowDocument doc = sampleDocument();
		FlowVersion version = new FlowVersion(1, doc, "2026-11-23T10:06:00Z");
		assertEquals(1, version.number());
		assertThrows(UnsupportedOperationException.class,
				() -> version.document().nodes().add(new Node("x", "y", java.util.Map.of())));
		assertThrows(UnsupportedOperationException.class,
				() -> version.document().wires().add(new Wire("x", "y")));
	}

	@Test
	void manifestIsHumanReadableJsonWithTheThreadAsMarkdown() throws Exception {
		FlowStore store = new FlowStore();
		Path file = workDir.resolve("study.flow.zip");
		store.save(sampleDocument(), sampleThread(), file);

		Map<String, String> entries = new java.util.LinkedHashMap<>();
		try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(file), StandardCharsets.UTF_8)) {
			ZipEntry entry;
			while ((entry = zip.getNextEntry()) != null) {
				entries.put(entry.getName(), new String(zip.readAllBytes(), StandardCharsets.UTF_8));
			}
		}
		assertEquals(java.util.Set.of("manifest.json", "thread.md"), entries.keySet());
		assertTrue(entries.get("manifest.json").contains("\"grad-rate-study\""), "manifest names the flow");
		assertTrue(entries.get("manifest.json").contains("\"import-csv\""), "manifest is readable JSON, not binary");
		assertTrue(entries.get("thread.md").contains("Which factors predict graduation rate?"),
				"thread is plain markdown text");
		assertTrue(entries.get("thread.md").startsWith("# "), "thread.md is markdown");
	}
}
