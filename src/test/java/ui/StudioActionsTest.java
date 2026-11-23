package ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import core.engine.GateState;
import core.engine.RunState;
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
 * The shell's run controls drive the engine primitives through the gated order, and run
 * events reach a bound dock by push - the UI never polls the engine.
 */
class StudioActionsTest {

	@TempDir
	Path workDir;

	@Test
	void controlsRunTheGatedSequenceAndPushEventsToTheDock() throws Exception {
		Files.writeString(workDir.resolve("data.csv"), "hours,gradrate\n2,50\n4,60\n");
		FlowDocument doc = new FlowDocument("hand-built-flow", 1,
				List.of(new Node("import-1", "import-csv", Map.of("path", "data.csv"))),
				List.of(), 7L,
				new Provenance("hand-built", "harry", "2026-11-23T12:00:00Z"), "thread-1", List.of());

		StudioActions actions = new StudioActions(core.engine.RunConfig.seeded(7L).workDir(workDir));
		LogDock.LogDockModel dock = new LogDock.LogDockModel();
		actions.bind(dock);

		actions.propose(doc, "card-1");
		assertEquals(GateState.PROPOSED, actions.gateState());
		actions.confirmBuild("card-1");
		actions.materialize();
		assertEquals(GateState.BUILT, actions.gateState());
		actions.confirmRun();
		core.engine.RunHandle handle = actions.startRun();
		assertEquals(RunState.COMPLETED, handle.await().state());

		assertEquals(GateState.COMPLETED, actions.gateState());
		assertTrue(dock.visible().stream().anyMatch(e -> e.type().equals("run-started")),
				"the dock saw the run by push, not polling");
		assertTrue(dock.visible().stream().anyMatch(e -> e.type().equals("node-green")
				&& e.nodeId().equals("import-1")), "node events reached the dock");
	}

	@Test
	void libraryTreeRowsComeFromTheLibrary() throws Exception {
		java.nio.file.Path workspace = workDir.resolve("workspace");
		core.persist.Library library = new core.persist.Library(workspace);
		LibraryPanel.LibraryTreeModel tree = new LibraryPanel.LibraryTreeModel(library);
		List<String> rows = tree.rows();
		assertTrue(rows.contains("demo:gradrate-ols"), "demos list as read-only entries");
		library.createProject("thesis");
		assertTrue(tree.rows().contains("project:thesis"), "projects list under their root");
		assertTrue(tree.reload().rows().contains("project:thesis"), "reload re-reads the library");
	}
}
