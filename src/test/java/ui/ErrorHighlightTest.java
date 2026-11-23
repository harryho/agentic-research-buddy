package ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import core.engine.RunEvent;
import core.engine.RunState;
import core.model.FlowDocument;
import core.model.Node;
import core.model.Provenance;
import core.model.Wire;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Clicking an error row in the dock selects and highlights the failing node on the canvas
 * and hands the node to the chat's diagnosis surface (the diagnosis content itself is
 * filled in by the caller). The chain runs on real engine error events.
 */
class ErrorHighlightTest {

	@TempDir
	Path workDir;

	private void wireChain(LogDock dock, CanvasPanel canvas, AgentChatPanel chat) {
		dock.onError(event -> {
			canvas.errorClick(event.nodeId(), event.message());
			chat.model().openDiagnosis(event.nodeId(), event.message());
		});
	}

	@Test
	void errorClickSelectsHighlightsAndHandsOffToChat() {
		LogDock dock = new LogDock();
		CanvasPanel canvas = new CanvasPanel();
		AgentChatPanel chat = new AgentChatPanel();
		wireChain(dock, canvas, chat);

		RunEvent info = new RunEvent("2026-11-23T12:00:00Z", 1, "info", "import-1", "node-started", "");
		RunEvent failure = new RunEvent("2026-11-23T12:00:01Z", 2, "error", "import-1", "node-error",
				"dataset not found: absent.csv");
		dock.model().onEvent(info);
		dock.model().onEvent(failure);

		AtomicReference<RunEvent> clicked = new AtomicReference<>();
		dock.onError(clicked::set);

		dock.model().clickEvent(info);
		assertEquals(null, clicked.get(), "info rows are not error clicks");

		dock.model().clickEvent(failure);
		assertEquals("import-1", clicked.get().nodeId(), "the error row carries the node reference");
		assertEquals("import-1", canvas.selectedNodeId(), "the failing node is selected");
		assertEquals("import-1", canvas.errorNodeId(), "the failing node is highlighted");
		assertTrue(chat.model().transcript().stream()
				.anyMatch(m -> m.text().contains("diagnosis requested for node import-1")),
				"the chat received the diagnosis handoff");
	}

	@Test
	void theChainRunsOnRealEngineErrors() throws Exception {
		FlowDocument bad = new FlowDocument("broken-flow", 1,
				List.of(new Node("import-1", "import-csv", Map.of("path", "absent.csv"))),
				List.of(), 7L,
				new Provenance("hand-built", "harry", "2026-11-23T12:00:00Z"), "thread-1", List.of());
		Files.createDirectories(workDir);

		StudioActions actions = new StudioActions(core.engine.RunConfig.seeded(7L).workDir(workDir));
		LogDock dock = new LogDock();
		CanvasPanel canvas = new CanvasPanel();
		AgentChatPanel chat = new AgentChatPanel();
		actions.bind(dock.model());
		wireChain(dock, canvas, chat);

		actions.propose(bad, "card-1");
		actions.confirmBuild("card-1");
		actions.materialize();
		actions.confirmRun();
		assertEquals(RunState.FAILED_RUN, actions.startRun().await().state());

		List<RunEvent> errors = dock.model().visible().stream()
				.filter(e -> e.level().equals("error")).toList();
		assertEquals(1, errors.size(), "the real node-error reached the dock");
		dock.model().clickEvent(errors.get(0));
		assertEquals("import-1", canvas.selectedNodeId(), "selection rides the real event");
		assertTrue(chat.model().transcript().stream()
				.anyMatch(m -> m.text().contains("diagnosis requested for node import-1")),
				"the handoff is live on a real failure");
	}
}
