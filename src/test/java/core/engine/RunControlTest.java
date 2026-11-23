package core.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import core.model.FlowDocument;
import core.model.Node;
import core.model.Provenance;
import core.model.Wire;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RunControlTest {

	@TempDir
	Path workDir;

	private final CountDownLatch hold = new CountDownLatch(1);

	private FlowDocument flowOf(List<Node> nodes, List<Wire> wires) {
		return new FlowDocument("study", 1, nodes, wires, 1L,
				new Provenance("hand-built", "harry", "2026-11-23T10:00:00Z"), "thread-1", List.of());
	}

	private FlowEngine readyEngine(FlowDocument doc) throws Exception {
		return readyEngine(doc, true);
	}

	private FlowEngine readyEngine(FlowDocument doc, boolean materializeNow) throws Exception {
		Files.writeString(workDir.resolve("mini.csv"), "a,b\n1,2\n");
		FlowEngine engine = new FlowEngine(RunConfig.seeded(1L).workDir(workDir));
		engine.registerExecutor("copy", (node, ctx) -> ctx.soleInput());
		engine.registerExecutor("hold", (node, ctx) -> {
			hold.await(10, TimeUnit.SECONDS);
			return "held";
		});
		engine.registerExecutor("emit", (node, ctx) -> node.params().get("value"));
		engine.registerExecutor("tag", (node, ctx) -> String.valueOf(ctx.soleInput()) + node.params().get("suffix"));
		engine.registerExecutor("capture", (node, ctx) -> {
			Object received = ctx.input(String.valueOf(node.params().get("from")));
			if (!node.params().get("expect").equals(received)) {
				throw new IllegalStateException("capture node '" + node.id() + "' expected "
						+ node.params().get("expect") + " but received " + received);
			}
			return received;
		});
		engine.propose(doc, "card-1", "buddy");
		engine.userConfirmBuild("card-1", "harry");
		if (materializeNow) {
			engine.materialize("harry");
		}
		return engine;
	}

	@Test
	void diamondFanOutDeliversWireSpecificValuesInDependencyOrder() throws Exception {
		FlowDocument doc = flowOf(
				List.of(
						new Node("a", "emit", Map.of("value", "seed")),
						new Node("b", "tag", Map.of("suffix", "-from-B")),
						new Node("c", "tag", Map.of("suffix", "-from-C")),
						new Node("e", "capture", Map.of("from", "b", "expect", "seed-from-B")),
						new Node("f", "capture", Map.of("from", "c", "expect", "seed-from-C"))),
				List.of(new Wire("a", "b"), new Wire("a", "c"), new Wire("b", "e"), new Wire("c", "f")));
		FlowEngine engine = readyEngine(doc);
		engine.userConfirmRun("harry");
		RunResult result = engine.startRun("harry").await();

		assertEquals(RunState.COMPLETED, result.state(), "each sink proves it received its own wire's value");
		assertEquals("green", result.nodeStatus("e"));
		assertEquals("green", result.nodeStatus("f"));
		List<String> startedOrder = engine.runLogReplay().stream()
				.filter(e -> e.type().equals("node-started"))
				.map(RunEvent::nodeId)
				.toList();
		assertEquals(List.of("a", "b", "c", "e", "f"), startedOrder,
				"execution order respects every wire and is deterministic");
	}

	@Test
	void fanInAtAnInputPortIsABuildTimeValidationError() throws Exception {
		FlowDocument doc = flowOf(
				List.of(
						new Node("a", "emit", Map.of("value", "seed")),
						new Node("b", "tag", Map.of("suffix", "-B")),
						new Node("c", "tag", Map.of("suffix", "-C")),
						new Node("d", "capture", Map.of("from", "a", "expect", "seed"))),
				List.of(new Wire("a", "d"), new Wire("b", "d"), new Wire("c", "d")));
		FlowEngine engine = readyEngine(doc, false);

		assertThrows(GateValidationException.class, () -> engine.materialize("harry"));
		assertEquals(GateState.CONFIRMED_BUILD, engine.gateState(),
				"a failed build is a refusal, not a state - the gate stays for re-attempt");
		assertTrue(engine.gateEvents().stream().anyMatch(e -> e.kind().equals("validation")
				&& e.detail() != null && e.detail().contains("d")), "the violation is explicitly logged");

		Path zip = workDir.resolve("failed-build.flow.zip");
		new core.persist.FlowStore().save(engine.document(),
				new core.model.RequirementThread("t-1", List.of()), zip);
		FlowEngine reloaded = new FlowEngine(RunConfig.seeded(1L).workDir(workDir.resolve("reload")));
		reloaded.restore(new core.persist.FlowStore().load(zip).document());
		assertTrue(reloaded.gateEvents().stream().anyMatch(e -> e.kind().equals("validation")),
				"the validation row survives the save immediately after a failed build");
	}

	@Test
	void runToNodeStopsAfterTheTarget() throws Exception {
		FlowDocument doc = flowOf(
				List.of(
						new Node("a", "import-csv", Map.of("path", "mini.csv")),
						new Node("b", "copy", Map.of()),
						new Node("c", "prediction-table", Map.of())),
				List.of(new Wire("a", "b"), new Wire("b", "c")));
		FlowEngine engine = readyEngine(doc);
		engine.userConfirmRun("harry");
		RunResult result = engine.runTo("b", "harry").await();

		assertEquals(RunState.STOPPED, result.state(), "run-to-node stops by contract after the target");
		assertEquals("green", result.nodeStatus("a"));
		assertEquals("green", result.nodeStatus("b"));
		assertEquals("idle", result.nodeStatus("c"));
		List<String> startedOrder = engine.runLogReplay().stream()
				.filter(e -> e.type().equals("node-started"))
				.map(RunEvent::nodeId)
				.toList();
		assertEquals(List.of("a", "b"), startedOrder);
		assertEquals(GateState.STOPPED, engine.gateState());
		RunEvent last = engine.runLogReplay().get(engine.runLogReplay().size() - 1);
		assertEquals("run-stopped", last.type(), "run-to-node terminates with a run-stopped line");
		assertEquals("target reached", last.message());
	}

	@Test
	void stopMidRunLandsStoppedAtTheNodeBoundary() throws Exception {
		AtomicBoolean tailStarted = new AtomicBoolean(false);
		FlowDocument doc = flowOf(
				List.of(
						new Node("import-csv-1", "import-csv", Map.of("path", "mini.csv")),
						new Node("hold-1", "hold", Map.of()),
						new Node("prediction-table-1", "prediction-table", Map.of())),
				List.of(new Wire("import-csv-1", "hold-1"), new Wire("hold-1", "prediction-table-1")));
		FlowEngine engine = readyEngine(doc);
		engine.registerExecutor("prediction-table", (node, ctx) -> {
			tailStarted.set(true);
			return ctx.soleInput();
		});
		engine.userConfirmRun("harry");
		RunHandle run = engine.startRun("harry");

		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
		while (System.nanoTime() < deadline && !"running".equals(engine.nodeStatus("hold-1"))) {
			Thread.sleep(10);
		}
		assertEquals("running", engine.nodeStatus("hold-1"));

		engine.stop("harry");
		hold.countDown();
		RunResult result = run.await();

		assertEquals(RunState.STOPPED, result.state());
		assertEquals("green", result.nodeStatus("hold-1"), "the in-flight node finishes");
		assertFalse(tailStarted.get(), "the next node never starts after stop");
		assertEquals(GateState.STOPPED, engine.gateState());
		RunEvent last = engine.runLogReplay().get(engine.runLogReplay().size() - 1);
		assertEquals("run-stopped", last.type());
		assertEquals("stop requested", last.message());
	}

	@Test
	void supersededRunLogsRunInvalidatedIntoTheRunLog() throws Exception {
		FlowDocument doc = flowOf(
				List.of(
						new Node("import-csv-1", "import-csv", Map.of("path", "mini.csv")),
						new Node("hold-1", "hold", Map.of()),
						new Node("prediction-table-1", "prediction-table", Map.of())),
				List.of(new Wire("import-csv-1", "hold-1"), new Wire("hold-1", "prediction-table-1")));
		FlowEngine engine = readyEngine(doc);
		engine.userConfirmRun("harry");
		RunHandle run = engine.startRun("harry");

		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
		while (System.nanoTime() < deadline && !"running".equals(engine.nodeStatus("hold-1"))) {
			Thread.sleep(10);
		}
		engine.applyEdit(doc, "agent");
		hold.countDown();
		run.await();

		assertTrue(engine.runLogReplay().stream().anyMatch(e -> e.type().equals("run-invalidated")),
				"the run log itself carries the run-invalidated event");
		assertTrue(engine.runLogReplay().stream()
				.filter(e -> e.type().equals("run-invalidated"))
				.allMatch(e -> e.message().equals("superseded") && e.level().equals("warn")));
	}
}
