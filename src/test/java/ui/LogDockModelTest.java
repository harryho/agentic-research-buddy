package ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import core.engine.RunEvent;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The log dock's machine legs: replay from the archive, live push, bounded view, filter. */
class LogDockModelTest {

	private RunEvent event(long seq, String level, String node, String type, String message) {
		return new RunEvent("2026-11-23T12:00:00Z", seq, level, node, type, message == null ? "" : message);
	}

	@Test
	void replaySeedsAndLivePushAppends() {
		LogDock.LogDockModel model = new LogDock.LogDockModel();
		model.replay(List.of(event(1, "info", "a", "node-started", null), event(2, "error", "b", "node-error", "boom")));
		assertEquals(2, model.visible().size());
		model.onEvent(event(3, "info", "c", "node-green", null));
		assertEquals(3, model.visible().size(), "live events append after the replay seed");
		assertEquals("node-green", model.visible().get(2).type());
	}

	@Test
	void viewIsBoundedToTheNewestHundred() {
		LogDock.LogDockModel model = new LogDock.LogDockModel();
		for (long seq = 1; seq <= 130; seq++) {
			model.onEvent(event(seq, "info", "", "tick", null));
		}
		assertEquals(100, model.visible().size(), "the dock is a bounded window, not the archive");
		assertEquals(130, model.visible().get(99).seq(), "the newest events stay visible");
		assertEquals(31, model.visible().get(0).seq(), "the head trims");
	}

	@Test
	void filterKeepsMatchingRowsOnly() {
		LogDock.LogDockModel model = new LogDock.LogDockModel();
		model.replay(List.of(
				event(1, "info", "a", "node-started", null),
				event(2, "error", "b", "node-error", "boom"),
				event(3, "info", "c", "node-green", null)));
		model.setFilter("error");
		assertEquals(1, model.visible().size());
		assertEquals("b", model.visible().get(0).nodeId());
		model.setFilter("");
		assertEquals(3, model.visible().size(), "clearing the filter restores the window");
		assertTrue(model.visible().stream().anyMatch(e -> e.seq() == 2));
	}
}
