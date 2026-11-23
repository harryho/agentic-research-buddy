package core.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import core.model.ConfirmHistoryEntry;
import core.model.FlowDocument;
import core.model.Node;
import core.model.Provenance;
import core.model.RequirementThread;
import core.model.Wire;
import core.persist.FlowStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfirmHistoryPersistenceTest {

	@TempDir
	Path workDir;

	private FlowDocument flow() {
		return new FlowDocument("study", 1,
				List.of(
						new Node("import-csv-1", "import-csv", Map.of("path", "mini.csv")),
						new Node("prediction-table-1", "prediction-table", Map.of())),
				List.of(new Wire("import-csv-1", "prediction-table-1")),
				1L,
				new Provenance("hand-built", "harry", "2026-11-23T10:00:00Z"),
				"thread-1",
				List.of());
	}

	private FlowEngine readyEngine(Path dir) throws Exception {
		Files.createDirectories(dir);
		Files.writeString(dir.resolve("mini.csv"), "a,b\n1,2\n");
		FlowEngine engine = new FlowEngine(RunConfig.seeded(1L).workDir(dir));
		engine.propose(flow(), "card-1", "buddy");
		engine.userConfirmBuild("card-1", "harry");
		engine.materialize("harry");
		engine.userConfirmRun("harry");
		return engine;
	}

	private Path save(FlowEngine engine, String name) {
		Path zip = workDir.resolve(name);
		new FlowStore().save(engine.document(),
				new RequirementThread("thread-1", List.of()), zip);
		return zip;
	}

	@Test
	void editedHistoryRestoresAnIdleGateAndRefusalsStayConsistent() throws Exception {
		FlowEngine engine = readyEngine(workDir.resolve("a"));
		assertEquals(RunState.COMPLETED, engine.startRun("harry").await().state());
		engine.applyEdit(flow(), "agent");

		Path zip = save(engine, "edited.flow.zip");
		FlowEngine reloaded = new FlowEngine(RunConfig.seeded(1L).workDir(workDir.resolve("b")));
		reloaded.restore(new FlowStore().load(zip).document());

		assertEquals(GateState.IDLE, reloaded.gateState(), "the persisted history ends with an edit");
		assertThrows(GateRefusalException.class, () -> reloaded.startRun("harry"),
				"refusal behaviour matches the persisted history");
		assertTrue(reloaded.gateEvents().stream().anyMatch(e -> e.kind().equals("edit")
				&& e.actor().equals("agent")), "the audit trail survives restart");
		assertTrue(reloaded.gateEvents().stream().anyMatch(e -> e.kind().equals("confirm")), "confirm rows survive");
	}

	@Test
	void confirmedUneditedHistoryRestoresAStandingConfirm() throws Exception {
		FlowEngine engine = readyEngine(workDir.resolve("a"));
		assertEquals(RunState.COMPLETED, engine.startRun("harry").await().state());

		Path zip = save(engine, "confirmed.flow.zip");
		Files.createDirectories(workDir.resolve("b"));
		Files.writeString(workDir.resolve("b").resolve("mini.csv"), "a,b\n1,2\n");
		FlowEngine reloaded = new FlowEngine(RunConfig.seeded(1L).workDir(workDir.resolve("b")));
		reloaded.restore(new FlowStore().load(zip).document());

		assertEquals(RunState.COMPLETED, reloaded.startRun("harry").await().state(),
				"the standing confirm derives from the persisted confirm rows");
	}

	@Test
	void refusalRowsSurviveRestart() throws Exception {
		Files.createDirectories(workDir.resolve("a"));
		Files.writeString(workDir.resolve("a").resolve("mini.csv"), "a,b\n1,2\n");
		FlowEngine engine = new FlowEngine(RunConfig.seeded(1L).workDir(workDir.resolve("a")));
		engine.propose(flow(), "card-1", "buddy");
		assertThrows(GateRefusalException.class, () -> engine.startRun("harry"));

		Path zip = save(engine, "refused.flow.zip");
		FlowEngine reloaded = new FlowEngine(RunConfig.seeded(1L).workDir(workDir.resolve("b")));
		reloaded.restore(new FlowStore().load(zip).document());

		assertTrue(reloaded.gateEvents().stream().anyMatch(e -> e.kind().equals("refusal")
				&& e.action().equals("run") && e.stateAt().equals("PROPOSED")),
				"refusal audit rows persist with action, actor and state at refusal");
	}
}
