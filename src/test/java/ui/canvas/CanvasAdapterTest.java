package ui.canvas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import core.engine.GateState;
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
import ui.LogDock;
import ui.StudioActions;

/**
 * The canvas edits the flow through the same document API the agent uses. Moves are
 * presentation: layout persists outside the document, the content hash never changes, the
 * gate is untouched, no audit row lands. Re-wire, re-parameterize, and delete are semantic
 * edits: edit state, audit row, hash change - identical to agent edits. Hand-built and
 * agent-built documents are format-identical.
 */
class CanvasAdapterTest {

	@TempDir
	Path workDir;

	private StudioActions gatedEngine() throws Exception {
		Files.writeString(workDir.resolve("data.csv"), "hours,gradrate\n2,50\n4,60\n");
		StudioActions actions = new StudioActions(core.engine.RunConfig.seeded(7L).workDir(workDir));
		actions.propose(doc("hand-flow"), "card-1");
		actions.confirmBuild("card-1");
		return actions;
	}

	private FlowDocument doc(String name) {
		return new FlowDocument(name, 1,
				List.of(
						new Node("import-1", "import-csv", Map.of("path", "data.csv")),
						new Node("forest-1", "random-forest", Map.of("target", "gradrate", "features", "hours")),
						new Node("eval-1", "eval-regression", Map.of("actual", "gradrate", "predicted", "predicted"))),
				List.of(new Wire("import-1", "forest-1"), new Wire("forest-1", "eval-1")),
				7L, new Provenance("hand-built", "harry", "2026-11-23T12:00:00Z"), "thread-1", List.of());
	}

	@Test
	void moveNodePersistsLayoutWithoutTouchingTheDocument() throws Exception {
		StudioActions actions = gatedEngine();
		LayoutStore layout = new LayoutStore(workDir.resolve("layout"));
		FlowGraphAdapter adapter = new FlowGraphAdapter(actions, layout);
		String hashBefore = actions.document().contentHash();
		int eventsBefore = actions.engine().gateEvents().size();
		GateState stateBefore = actions.gateState();

		adapter.moveNode("forest-1", 220, 140);

		assertEquals(hashBefore, actions.document().contentHash(), "moves are presentation: no hash change");
		assertEquals(stateBefore, actions.gateState(), "moves never trip the gate");
		assertEquals(eventsBefore, actions.engine().gateEvents().size(), "moves land no audit row");
		assertEquals(new LayoutStore.Pos(220, 140), adapter.positions().get("forest-1"));

		FlowGraphAdapter reloaded = new FlowGraphAdapter(actions, new LayoutStore(workDir.resolve("layout")));
		assertEquals(new LayoutStore.Pos(220, 140), reloaded.positions().get("forest-1"),
				"layout persists outside the document and reloads");
	}

	@Test
	void rewireAndParameterAndDeleteAreSemanticEdits() throws Exception {
		StudioActions actions = gatedEngine();
		FlowGraphAdapter adapter = new FlowGraphAdapter(actions, new LayoutStore(workDir.resolve("layout")));
		String hashBefore = actions.document().contentHash();

		adapter.setParameter("forest-1", "trees", 40);
		assertEquals(GateState.IDLE, actions.gateState(), "re-parameterizing is a semantic edit");
		assertTrue(actions.document().contentHash().equals(hashBefore) == false, "the hash changes");
		assertTrue(actions.engine().gateEvents().stream()
				.anyMatch(e -> e.kind().equals("edit")), "an audit edit row landed");
		assertEquals(40, actions.document().nodes().stream()
				.filter(n -> n.id().equals("forest-1")).findFirst().orElseThrow().params().get("trees"));

		actions.propose(doc("hand-flow"), "card-2");
		actions.confirmBuild("card-2");
		adapter.rewire("import-1", "eval-1");
		assertEquals(GateState.IDLE, actions.gateState(), "re-wiring is a semantic edit");
		assertTrue(actions.document().wires().stream()
				.anyMatch(w -> w.from().equals("import-1") && w.to().equals("eval-1")), "the new wire is in");
		assertTrue(actions.document().wires().stream()
				.noneMatch(w -> w.to().equals("eval-1") && !w.from().equals("import-1")),
				"the replaced wire is gone - fan-in stays illegal");

		actions.propose(doc("hand-flow"), "card-3");
		actions.confirmBuild("card-3");
		adapter.deleteNode("forest-1");
		assertTrue(actions.document().nodes().stream().noneMatch(n -> n.id().equals("forest-1")));
		assertTrue(actions.document().wires().stream().noneMatch(w ->
				w.from().equals("forest-1") || w.to().equals("forest-1")), "incident wires go with the node");
	}

	@Test
	void handBuiltAndAgentBuiltAreFormatIdentical() throws Exception {
		StudioActions hand = gatedEngine();
		FlowGraphAdapter adapter = new FlowGraphAdapter(hand, new LayoutStore(workDir.resolve("layout")));
		adapter.setParameter("forest-1", "trees", 40);

		StudioActions agent = new StudioActions(core.engine.RunConfig.seeded(7L).workDir(workDir));
		agent.propose(doc("hand-flow"), "card-1");
		agent.confirmBuild("card-1");
		FlowDocument edited = new FlowDocument("hand-flow", 1,
				List.of(
						new Node("import-1", "import-csv", Map.of("path", "data.csv")),
						new Node("forest-1", "random-forest", Map.of("target", "gradrate", "features", "hours",
								"trees", 40)),
						new Node("eval-1", "eval-regression", Map.of("actual", "gradrate", "predicted", "predicted"))),
				List.of(new Wire("import-1", "forest-1"), new Wire("forest-1", "eval-1")),
				7L, new Provenance("hand-built", "harry", "2026-11-23T12:00:00Z"), "thread-1", List.of());
		agent.applyEdit(edited);

		// content identity is the ruled comparison: same name, nodes, wires, seed - the gate
		// histories differ only in audit timestamps, which are not document content
		assertEquals(agent.document().contentHash(), hand.document().contentHash(),
				"one document kind: the canvas produces the agent's format exactly");
		assertEquals(agent.document().nodes(), hand.document().nodes(),
				"node lists are element-identical, params included");
	}

	@Test
	void graphModelMirrorsTheDocument() throws Exception {
		StudioActions actions = gatedEngine();
		FlowGraphAdapter adapter = new FlowGraphAdapter(actions, new LayoutStore(workDir.resolve("layout")));
		org.jgrapht.Graph<NodeCell, String> graph = adapter.graph();
		assertEquals(3, graph.vertexSet().size());
		assertEquals(2, graph.edgeSet().size());
		assertTrue(graph.vertexSet().stream().anyMatch(v -> v.id().equals("forest-1")
				&& v.type().equals("random-forest")));
		assertTrue(graph.containsVertex(new NodeCell("eval-1", "eval-regression")));
		assertFalse(graph.edgeSet().isEmpty());
	}
}
