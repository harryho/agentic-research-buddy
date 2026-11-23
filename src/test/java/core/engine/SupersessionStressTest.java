package core.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import core.model.FlowDocument;
import core.model.Node;
import core.model.Provenance;
import core.model.Wire;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Hammers the supersession boundary: edits land from a second thread while a multi-node run
 * is mid-flight, repeatedly. The contract must hold every round — the run stops at the next
 * node boundary, exactly one run-invalidated event, no node starts after it, every started
 * node finished green, and the gate ends idle.
 */
class SupersessionStressTest {

	@TempDir
	Path workDir;

	@Test
	void concurrentEditsAlwaysCancelTheRunAtTheBoundary() throws Exception {
		for (int round = 0; round < 25; round++) {
			Path dir = workDir.resolve("r" + round);
			Files.createDirectories(dir);
			Files.writeString(dir.resolve("mini.csv"), "a,b\n1,2\n");
			FlowEngine engine = new FlowEngine(RunConfig.seeded(round).workDir(dir));
			engine.registerExecutor("tick", (node, ctx) -> {
				Thread.sleep(2);
				return ctx.soleInput();
			});
			List<Node> nodes = new ArrayList<>();
			nodes.add(new Node("n000", "import-csv", Map.of("path", "mini.csv")));
			for (int i = 1; i < 20; i++) {
				nodes.add(new Node(String.format("n%03d", i), "tick", Map.of()));
			}
			List<Wire> wires = new ArrayList<>();
			for (int i = 1; i < 20; i++) {
				wires.add(new Wire(String.format("n%03d", i - 1), String.format("n%03d", i)));
			}
			FlowDocument doc = new FlowDocument("stress", 1, nodes, wires, (long) round,
					new Provenance("hand-built", "harry", "2026-11-23T10:00:00Z"), "thread-1", List.of());
			engine.propose(doc, "card-1", "buddy");
			engine.userConfirmBuild("card-1", "harry");
			engine.materialize("harry");
			engine.userConfirmRun("harry");

			RunHandle run = engine.startRun("harry");
			Thread editor = new Thread(() -> {
				for (int i = 0; i < 5; i++) {
					engine.applyEdit(doc, "agent");
					try {
						Thread.sleep(3);
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
						return;
					}
				}
			});
			editor.start();
			RunResult result = run.await();
			editor.join();

			assertEquals(RunState.STOPPED, result.state(), "round " + round);
			assertEquals(GateState.IDLE, engine.gateState(), "round " + round);
			List<RunEvent> log = engine.runLogReplay();
			long invalidations = log.stream().filter(e -> e.type().equals("run-invalidated")).count();
			assertEquals(1, invalidations, "round " + round + " logs exactly one run-invalidated");
			int at = -1;
			for (int i = 0; i < log.size(); i++) {
				if (log.get(i).type().equals("run-invalidated")) {
					at = i;
					break;
				}
			}
			final int invalidationAt = at;
			assertTrue(log.stream().skip(invalidationAt + 1).noneMatch(e -> e.type().equals("node-started")),
					"round " + round + ": no node starts after supersession");
			long started = log.stream().filter(e -> e.type().equals("node-started")).count();
			long green = log.stream().filter(e -> e.type().equals("node-green")).count();
			assertEquals(started, green, "round " + round + ": every started node finished green");
		}
	}
}
