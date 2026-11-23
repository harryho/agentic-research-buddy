package ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import core.engine.RunState;
import core.model.FlowDocument;
import core.model.Node;
import core.model.Provenance;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ui.canvas.FlowGraphAdapter;
import ui.canvas.LayoutStore;

/** A highlighted node keeps its highlight while not green, and clears once it is. */
class HighlightClearTest {

	@TempDir
	Path workDir;

	private StudioActions engineWithImportNode() throws Exception {
		Files.writeString(workDir.resolve("data.csv"), "hours,gradrate\n2,50\n4,60\n");
		StudioActions actions = new StudioActions(core.engine.RunConfig.seeded(7L).workDir(workDir));
		actions.propose(new FlowDocument("tiny", 1,
				List.of(new Node("n1", "import-csv", Map.of("path", "data.csv"))), List.of(), 7L,
				new Provenance("hand-built", "harry", "2026-11-23T12:00:00Z"), "t", List.of()), "c1");
		actions.confirmBuild("c1");
		actions.materialize();
		actions.confirmRun();
		return actions;
	}

	@Test
	void highlightKeepsWhileIdleAndClearsOnceGreen() throws Exception {
		StudioActions actions = engineWithImportNode();
		CanvasPanel canvas = new CanvasPanel();
		canvas.bind(new FlowGraphAdapter(actions, new LayoutStore(workDir.resolve("layout"))));

		canvas.errorClick("n1", "boom");
		assertEquals("n1", canvas.errorNodeId(), "highlighted after an error click");
		canvas.refresh();
		assertEquals("n1", canvas.errorNodeId(), "the node has not run - the highlight stays");

		assertEquals(RunState.COMPLETED, actions.startRun().await().state(), "the node runs green");
		canvas.refresh();
		assertNull(canvas.errorNodeId(), "a green node clears its highlight on refresh");
	}
}
