package core.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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

class GateInvariantTest {

	@TempDir
	Path workDir;

	private final CountDownLatch hold = new CountDownLatch(1);

	private FlowDocument doc(String name, String paramValue) {
		return new FlowDocument(
				name,
				1,
				List.of(
						new Node("import-csv-1", "import-csv", Map.of("path", paramValue)),
						new Node("hold-1", "hold", Map.of()),
						new Node("prediction-table-1", "prediction-table", Map.of())),
				List.of(new Wire("import-csv-1", "hold-1"), new Wire("hold-1", "prediction-table-1")),
				1L,
				new Provenance("hand-built", "harry", "2026-11-23T10:00:00Z"),
				"thread-1",
				List.of());
	}

	private FlowEngine readyEngine(FlowDocument doc) {
		FlowEngine engine = new FlowEngine(RunConfig.seeded(1L).workDir(workDir));
		engine.propose(doc, "card-1", "buddy");
		engine.userConfirmBuild("card-1", "harry");
		engine.materialize("harry");
		return engine;
	}

	private void awaitNodeStatus(FlowEngine engine, String nodeId, String expected) throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
		while (System.nanoTime() < deadline) {
			if (expected.equals(engine.nodeStatus(nodeId))) {
				return;
			}
			Thread.sleep(10);
		}
		assertEquals(expected, engine.nodeStatus(nodeId), "node never reached status");
	}

	@Test
	void scenario1_executeWithoutConfirmIsRefusedLoggedAndLeavesDocumentUntouched() throws Exception {
		FlowEngine engine = new FlowEngine(RunConfig.seeded(1L).workDir(workDir));
		GateRefusalException refusedInIdle = assertThrows(GateRefusalException.class,
				() -> engine.startRun("agent"));
		assertEquals(GateState.IDLE, refusedInIdle.stateAt());

		Files.writeString(workDir.resolve("mini.csv"), "a,b\n1,2\n");
		engine.propose(doc("study", "mini.csv"), "card-1", "buddy");
		String hashBefore = engine.document().contentHash();
		GateRefusalException refusedInProposed = assertThrows(GateRefusalException.class,
				() -> engine.startRun("agent"));
		assertEquals(GateState.PROPOSED, refusedInProposed.stateAt());
		assertEquals(hashBefore, engine.document().contentHash());
		assertEquals(GateState.PROPOSED, engine.gateState());

		List<GateEvent> events = engine.gateEvents();
		assertEquals(2, events.stream().filter(e -> e.kind().equals("refusal")).count());
		assertTrue(events.stream().filter(e -> e.kind().equals("refusal"))
				.allMatch(e -> e.action().equals("run")),
				"refusal events carry the attempted action");
		assertTrue(events.stream().filter(e -> e.kind().equals("refusal"))
				.allMatch(e -> e.stateAt().equals("IDLE") || e.stateAt().equals("PROPOSED")));
	}

	@Test
	void scenario2_editAfterConfirmedBuildInvalidatesAndRefusesUntilReconfirm() throws Exception {
		FlowEngine engine = readyEngine(doc("study", "mini.csv"));
		engine.userConfirmRun("harry");

		FlowDocument edited = doc("study-renamed", "other.csv");
		engine.applyEdit(edited, "harry");
		assertEquals(GateState.IDLE, engine.gateState());

		GateRefusalException runRefused = assertThrows(GateRefusalException.class, () -> engine.startRun("harry"));
		assertEquals(GateState.IDLE, runRefused.stateAt());
		GateRefusalException reconfirmRefused = assertThrows(GateRefusalException.class,
				() -> engine.userConfirmRun("harry"));
		assertNotNull(reconfirmRefused);

		assertTrue(engine.gateEvents().stream().anyMatch(e -> e.kind().equals("edit") && e.actor().equals("harry")));
		assertEquals(edited.contentHash(), engine.document().contentHash());
	}

	@Test
	void scenario3_agentPathWithoutMintedConfirmIsRefusedIdentically() {
		FlowEngine engine = new FlowEngine(RunConfig.seeded(1L).workDir(workDir));
		GateRefusalException refused = assertThrows(GateRefusalException.class, () -> engine.startRun("agent"));
		assertEquals(GateState.IDLE, refused.stateAt());

		GateEvent refusal = engine.gateEvents().stream()
				.filter(e -> e.kind().equals("refusal"))
				.findFirst()
				.orElseThrow();
		assertEquals("agent", refusal.actor());

		for (var constructor : ConfirmEvent.class.getDeclaredConstructors()) {
			assertFalse(java.lang.reflect.Modifier.isPublic(constructor.getModifiers()),
					"no agent-visible way to construct a confirm event");
		}
	}

	@Test
	void scenario4_editWhileRunningCancelsAtNodeBoundaryLogsRunInvalidatedAndIdlesGate() throws Exception {
		Files.writeString(workDir.resolve("mini.csv"), "a,b\n1,2\n");
		AtomicBoolean tailStarted = new AtomicBoolean(false);
		FlowEngine engine = readyEngine(doc("study", "mini.csv"));
		engine.registerExecutor("hold", (node, ctx) -> {
			hold.await(10, TimeUnit.SECONDS);
			return "held";
		});
		engine.registerExecutor("prediction-table", (node, ctx) -> {
			tailStarted.set(true);
			return ctx.soleInput();
		});
		engine.userConfirmRun("harry");
		RunHandle run = engine.startRun("harry");

		awaitNodeStatus(engine, "hold-1", "running");
		engine.applyEdit(doc("study", "mini.csv"), "agent");
		assertEquals(GateState.IDLE, engine.gateState(), "edit invalidates the running confirm immediately");
		hold.countDown();

		RunResult result = run.await();
		assertEquals(RunState.STOPPED, result.state());
		assertEquals("green", result.nodeStatus("hold-1"), "in-flight node finishes on its pre-edit version");
		assertEquals("idle", result.nodeStatus("prediction-table-1"));
		assertFalse(tailStarted.get(), "no node starts after supersession");
		assertEquals("idle", engine.nodeStatus("prediction-table-1"));

		GateEvent invalidated = engine.gateEvents().stream()
				.filter(e -> e.kind().equals("run-invalidated"))
				.findFirst()
				.orElseThrow();
		assertEquals("superseded", invalidated.detail());
		assertEquals("agent", invalidated.actor());
		assertEquals(GateState.IDLE, engine.gateState());

		assertThrows(GateRefusalException.class, () -> engine.startRun("harry"));
	}
}
