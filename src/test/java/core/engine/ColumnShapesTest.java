package core.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import core.model.ConfirmHistoryEntry;
import core.model.FlowDocument;
import core.model.Node;
import core.model.Provenance;
import core.model.Wire;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Build-time column-shape validation: eval nodes need their actual/predicted columns present
 * in the wired upstream table shape, checked when the gate materializes - the same failure
 * family as fan-in (validation row on the document, gate stays CONFIRMED_BUILD).
 */
class ColumnShapesTest {

	@TempDir
	Path workDir;

	private FlowEngine engineFor(FlowDocument doc) throws Exception {
		Files.writeString(workDir.resolve("data.csv"), "hours,gradrate\n2,50\n4,60\n");
		FlowEngine engine = new FlowEngine(RunConfig.seeded(7L).workDir(workDir));
		engine.propose(doc, "card-1", "buddy");
		engine.userConfirmBuild("card-1", "harry");
		return engine;
	}

	private FlowDocument doc(List<Node> nodes, List<Wire> wires) {
		return new FlowDocument("shape-check", 1, nodes, wires, 7L,
				new Provenance("hand-built", "harry", "2026-11-23T12:00:00Z"), "thread-1", List.of());
	}

	@Test
	void evalWithoutAPredictedUpstreamIsAValidationRow() throws Exception {
		FlowDocument bad = doc(
				List.of(
						new Node("import-1", "import-csv", Map.of("path", "data.csv")),
						new Node("eval-1", "eval-regression", Map.of("actual", "gradrate", "predicted", "predicted"))),
				List.of(new Wire("import-1", "eval-1")));
		FlowEngine engine = engineFor(bad);
		GateValidationException e = assertThrows(GateValidationException.class, () -> engine.materialize("harry"));
		assertTrue(e.getMessage().contains("predicted"), "the diagnosis names the missing column");
		assertEquals(GateState.CONFIRMED_BUILD, engine.gateState(), "the gate does not advance on a bad shape");
		assertTrue(engine.gateEvents().stream().anyMatch(ev -> ev.kind().equals("validation")), "a validation event");
		FlowDocument stamped = engine.document();
		assertTrue(stamped.confirmHistory().stream().anyMatch(
				h -> h.action().equals("materialize") && h.kind().equals("validation")), "the row is on the document");
		ConfirmHistoryEntry row = stamped.confirmHistory().get(stamped.confirmHistory().size() - 1);
		assertEquals("eval-regression node 'eval-1': upstream table lacks column(s): predicted", row.detail());
	}

	@Test
	void evalBehindAScorerMaterializes() throws Exception {
		FlowDocument good = doc(
				List.of(
						new Node("import-1", "import-csv", Map.of("path", "data.csv")),
						new Node("forest-1", "random-forest", Map.of("target", "gradrate", "features", "hours")),
						new Node("eval-1", "eval-regression", Map.of("actual", "gradrate", "predicted", "predicted"))),
				List.of(new Wire("import-1", "forest-1"), new Wire("forest-1", "eval-1")));
		FlowEngine engine = engineFor(good);
		engine.materialize("harry");
		assertEquals(GateState.BUILT, engine.gateState(), "a well-wired eval chain materializes");
	}

	@Test
	void evalBehindOlsIsRejectedBecauseTheSummaryHasNoPredicted() throws Exception {
		FlowDocument bad = doc(
				List.of(
						new Node("import-1", "import-csv", Map.of("path", "data.csv")),
						new Node("ols-1", "ols-regression", Map.of("target", "gradrate", "features", "hours")),
						new Node("eval-1", "eval-classification", Map.of("actual", "gradrate", "predicted", "predicted"))),
				List.of(new Wire("import-1", "ols-1"), new Wire("ols-1", "eval-1")));
		FlowEngine engine = engineFor(bad);
		assertThrows(GateValidationException.class, () -> engine.materialize("harry"),
				"the coefficient summary has no predicted column");
	}

	@Test
	void missingDatasetStaysARunTimeFailureNotABuildOne() throws Exception {
		FlowDocument bad = doc(
				List.of(new Node("import-1", "import-csv", Map.of("path", "absent.csv"))),
				List.of());
		FlowEngine engine = engineFor(bad);
		engine.materialize("harry");
		assertEquals(GateState.BUILT, engine.gateState(),
				"an unreadable shape degrades the check - dataset failures stay run-time node errors");
	}
}
