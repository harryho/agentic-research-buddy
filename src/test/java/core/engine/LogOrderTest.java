package core.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import core.model.FlowDocument;
import core.model.Node;
import core.model.Provenance;
import core.model.Wire;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LogOrderTest {

	@TempDir
	Path workDir;

	@Test
	void twoHundredEventRunArchivesStrictlyOrderedJsonlAndBoundedDockReplay() throws Exception {
		List<Node> nodes = new ArrayList<>();
		List<Wire> wires = new ArrayList<>();
		nodes.add(new Node("n000", "import-csv", Map.of("path", "mini.csv")));
		for (int i = 1; i <= 99; i++) {
			nodes.add(new Node(String.format("n%03d", i), "copy", Map.of()));
			wires.add(new Wire(String.format("n%03d", i - 1), String.format("n%03d", i)));
		}
		FlowDocument doc = new FlowDocument("long", 1, nodes, wires, 1L,
				new Provenance("hand-built", "harry", "2026-11-23T10:00:00Z"), "thread-1", List.of());

		java.nio.file.Files.writeString(workDir.resolve("mini.csv"), "a,b\n1,2\n");
		FlowEngine engine = new FlowEngine(RunConfig.seeded(1L).workDir(workDir));
		engine.registerExecutor("copy", (node, ctx) -> ctx.soleInput());
		engine.propose(doc, "card-1", "buddy");
		engine.userConfirmBuild("card-1", "harry");
		engine.materialize("harry");
		engine.userConfirmRun("harry");
		RunHandle run = engine.startRun("harry");
		assertEquals(1, run.runId(), "the first run owns archive 1");
		RunResult result = run.await();
		assertEquals(RunState.COMPLETED, result.state());
		assertEquals(engine.runLogReplay(), engine.runArchive(1), "replay addresses the run's own archive");
		RunHandle second = engine.startRun("harry");
		assertEquals(2, second.runId(), "the second run owns archive 2");
		second.await();
		assertEquals(202, engine.runArchive(2).size(), "archive 2 is complete and separate");

		List<RunEvent> replay = engine.runLogReplay();
		long nodeEvents = replay.stream().filter(e -> e.type().startsWith("node-")).count();
		assertEquals(200, nodeEvents, "100 nodes x started+finished");
		assertEquals(202, replay.size(), "node events + run-started + run-finished");

		for (int i = 0; i < replay.size(); i++) {
			assertEquals(i + 1, replay.get(i).seq(), "sequence numbers are strictly ordered");
		}
		assertTrue(replay.stream().allMatch(e -> e.nodeId() != null), "every event is node-tagged (empty for run-level)");
		assertTrue(replay.stream().filter(e -> e.type().startsWith("node-"))
				.allMatch(e -> !e.nodeId().isEmpty()), "node events carry their node tag");

		List<RunEvent> dock = engine.runLogDock();
		assertEquals(RunLog.DOCK_BUFFER_CAPACITY, dock.size(), "the dock buffer is bounded");
		assertEquals(replay.subList(replay.size() - dock.size(), replay.size()), dock,
				"the dock holds exactly the most recent archive events");
	}
}
