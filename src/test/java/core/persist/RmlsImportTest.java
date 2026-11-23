package core.persist;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import core.engine.FlowEngine;
import core.engine.GateState;
import core.engine.RunConfig;
import core.model.Wire;
import core.registry.NodeRegistry;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The .rmls import slice: legacy flows whose nodes map to registry equivalents import as
 * buildable flows; unmapped legacy keys import as inert placeholders with warnings - never
 * silently dropped. Real corpus samples from the paradigm ancestor drive every leg.
 */
class RmlsImportTest {

	@TempDir
	Path workDir;

	private Path resource(String name) throws Exception {
		Path file = workDir.resolve(name);
		try (InputStream in = RmlsImportTest.class.getResourceAsStream("/legacy/" + name)) {
			if (in == null) {
				throw new IllegalStateException("sample missing: " + name);
			}
			Files.copy(in, file);
		}
		return file;
	}

	@Test
	void mappedSampleImportsToABuildableFlow() throws Exception {
		RmlsImportAdapter.Imported imported = RmlsImportAdapter.importFlow(resource("audit.rmls"));
		var types = imported.document().nodes().stream().map(n -> n.type()).toList();
		assertTrue(types.contains("import-csv"), "the legacy import maps to import-csv");
		assertTrue(types.contains("ols-regression"), "estimation.ols maps to ols-regression");
		assertTrue(types.contains("eval-regression"), "rsquare/pvalue map to eval-regression");
		assertTrue(types.contains("legacy-note"), "equation nodes are not registry equivalents");
		assertTrue(imported.warnings().stream().anyMatch(w -> w.contains("equation.mr")),
				"the unmapped key is named in a warning");
		assertTrue(!imported.warnings().stream().anyMatch(w -> w.contains("never")),
				"nothing is silently dropped");
		assertTrue(!imported.positions().isEmpty(), "positions are recovered for the layout");
		assertTrue(imported.document().nodes().size() == 6 && imported.document().wires().size() >= 4,
				"all six cells became nodes, wired as the legacy flow wired them: "
						+ imported.document().nodes().size() + "/" + imported.document().wires().size());

		// the mapped sample builds through the gated path end to end
		NodeRegistry registry = RmlsImportAdapter.withPlaceholder(NodeRegistry.standard());
		FlowEngine engine = new FlowEngine(RunConfig.seeded(7L).workDir(workDir), registry);
		engine.propose(imported.document(), "card-1", "buddy");
		engine.userConfirmBuild("card-1", "harry");
		engine.materialize("harry");
		engine.userConfirmRun("harry");
		assertEquals(GateState.CONFIRMED_RUN, engine.gateState(), "the imported flow builds green");
	}

	@Test
	void unmappedKeysBecomePlaceholdersWithWarnings() throws Exception {
		RmlsImportAdapter.Imported imported = RmlsImportAdapter.importFlow(resource("nnet-iris.rmls"));
		assertTrue(imported.warnings().stream().anyMatch(w -> w.contains("model.nn")), "nn named");
		assertTrue(imported.warnings().stream().anyMatch(w -> w.contains("model.predict")), "predict named");
		assertTrue(imported.warnings().stream().anyMatch(w -> w.contains("splitData")), "split named");
		var placeholders = imported.document().nodes().stream()
				.filter(n -> n.type().equals("legacy-note")).toList();
		assertEquals(3, placeholders.size(), "splitData, model.nn, model.predict are placeholders");
		assertTrue(placeholders.stream().allMatch(n -> n.params().containsKey("legacyKey")),
				"each placeholder keeps its legacy key");
		assertEquals(6, imported.document().nodes().size(), "every legacy cell became a node");
		assertTrue(imported.document().wires().size() >= 4, "legacy wiring carries over");
	}

	@Test
	void placeholdersRunInertAndGreen() throws Exception {
		Files.writeString(workDir.resolve("data.csv"), "hours,gradrate\n2,50\n4,60\n");
		NodeRegistry registry = RmlsImportAdapter.withPlaceholder(NodeRegistry.standard());
		FlowEngine engine = new FlowEngine(RunConfig.seeded(7L).workDir(workDir), registry);
		core.model.FlowDocument doc = new core.model.FlowDocument("note-run", 1,
				List.of(
						new core.model.Node("import-1", "import-csv", java.util.Map.of("path", "data.csv")),
						new core.model.Node("note-1", "legacy-note",
								java.util.Map.of("legacyKey", "model.nn"))),
				List.of(new Wire("import-1", "note-1")),
				7L, new core.model.Provenance("imported", "audit.rmls", "2026-11-23T15:00:00Z"),
				"thread-1", List.of());
		engine.propose(doc, "card-1", "buddy");
		engine.userConfirmBuild("card-1", "harry");
		engine.materialize("harry");
		engine.userConfirmRun("harry");
		core.engine.RunHandle handle = engine.startRun("harry");
		handle.future().get();
		assertTrue(engine.runLogReplay().stream()
				.anyMatch(e -> e.type().equals("run-completed") && e.level().equals("info")),
				"the placeholder runs green - inert, not fatal");
		assertTrue(engine.document().nodes().stream()
				.filter(n -> n.id().equals("note-1"))
				.findFirst().orElseThrow().type().equals("legacy-note"));
	}

	@Test
	void malformedLegacyFilesAreRefusedCleanly() throws Exception {
		Path bad = workDir.resolve("bad.rmls");
		Files.writeString(bad, "<this-is-not><the-legacy-format");
		try {
			RmlsImportAdapter.importFlow(bad);
			assertTrue(false, "a malformed file must be refused");
		} catch (java.io.IOException expected) {
			assertTrue(expected.getMessage().toLowerCase().contains("legacy"),
					"the refusal names the legacy source");
		}
	}
}
