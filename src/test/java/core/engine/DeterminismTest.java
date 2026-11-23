package core.engine;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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

class DeterminismTest {

	@TempDir
	Path workDir;

	private FlowDocument flow() {
		return new FlowDocument(
				"sample-study",
				1,
				List.of(
						new Node("import-csv-1", "import-csv", Map.of("path", "mini.csv")),
						new Node("sample-1", "random-sample", Map.of("rows", 2, "out", "sampled.csv")),
						new Node("prediction-table-1", "prediction-table", Map.of())),
				List.of(new Wire("import-csv-1", "sample-1"), new Wire("sample-1", "prediction-table-1")),
				1L,
				new Provenance("agent-built", "buddy", "2026-11-23T10:00:00Z"),
				"thread-1",
				List.of());
	}

	private RunResult runOnce(String logDir) throws Exception {
		Path dir = workDir.resolve(logDir);
		Files.createDirectories(dir);
		Files.writeString(dir.resolve("mini.csv"), "a,b\n1,2\n3,4\n5,6\n7,8\n");
		FlowEngine engine = new FlowEngine(RunConfig.seeded(42L).workDir(dir));
		registerRandomSample(engine);
		engine.propose(flow(), "card-1", "buddy");
		engine.userConfirmBuild("card-1", "harry");
		engine.materialize("harry");
		engine.userConfirmRun("harry");
		return engine.startRun("harry").await();
	}

	@Test
	void sameFlowInputsAndSeedTwiceGiveIdenticalOutputsAndLogLinesModuloTimestamps() throws Exception {
		RunResult first = runOnce("run-a");
		RunResult second = runOnce("run-b");

		assertEquals(RunState.COMPLETED, first.state());
		assertEquals(RunState.COMPLETED, second.state());
		assertEquals(first.nodeStatuses(), second.nodeStatuses());

		assertArrayEquals(Files.readAllBytes(workDir.resolve("run-a/sampled.csv")),
				Files.readAllBytes(workDir.resolve("run-b/sampled.csv")),
				"seeded sampling produces identical outputs");

		List<RunEvent> logA = stripTimestamps(workDir.resolve("run-a/runs/run-1.jsonl"));
		List<RunEvent> logB = stripTimestamps(workDir.resolve("run-b/runs/run-1.jsonl"));
		assertEquals(logA, logB, "log lines identical modulo timestamps");
	}

	private List<RunEvent> stripTimestamps(Path logFile) throws Exception {
		return java.util.Arrays.stream(Files.readString(logFile).split("\n"))
				.filter(line -> !line.isBlank())
				.map(line -> line.replaceAll("\"ts\":\"[^\"]*\"", "\"ts\":\"\""))
				.map(line -> new RunEvent("", 0, "raw", "", "", line))
				.toList();
	}

	@Test
	void rerunOfAnUneditedCompletedFlowNeedsNoNewConfirm() throws Exception {
		Path dir = workDir.resolve("rerun");
		Files.createDirectories(dir);
		Files.writeString(dir.resolve("mini.csv"), "a,b\n1,2\n3,4\n");
		FlowEngine engine = new FlowEngine(RunConfig.seeded(42L).workDir(dir));
		registerRandomSample(engine);
		engine.propose(flow(), "card-1", "buddy");
		engine.userConfirmBuild("card-1", "harry");
		engine.materialize("harry");
		engine.userConfirmRun("harry");
		assertEquals(RunState.COMPLETED, engine.startRun("harry").await().state());

		assertEquals(GateState.COMPLETED, engine.gateState());
		assertEquals(RunState.COMPLETED, engine.startRun("harry").await().state(),
				"standing confirm covers the re-run of an unedited flow");
	}

	@Test
	void editedFlowRefusesRerunUntilReconfirm() throws Exception {
		Path dir = workDir.resolve("edited");
		Files.createDirectories(dir);
		Files.writeString(dir.resolve("mini.csv"), "a,b\n1,2\n3,4\n");
		Files.writeString(dir.resolve("other.csv"), "a,b\n9,9\n");
		FlowEngine engine = new FlowEngine(RunConfig.seeded(42L).workDir(dir));
		registerRandomSample(engine);
		engine.propose(flow(), "card-1", "buddy");
		engine.userConfirmBuild("card-1", "harry");
		engine.materialize("harry");
		engine.userConfirmRun("harry");
		assertEquals(RunState.COMPLETED, engine.startRun("harry").await().state());

		engine.applyEdit(flow(), "harry");
		assertThrows(GateRefusalException.class, () -> engine.startRun("harry"));

		engine.propose(flow(), "card-2", "buddy");
		engine.userConfirmBuild("card-2", "harry");
		engine.materialize("harry");
		engine.userConfirmRun("harry");
		assertEquals(RunState.COMPLETED, engine.startRun("harry").await().state(),
				"fresh confirm re-opens the run");
	}

	/** Test-only seeded sampler: partial Fisher-Yates over row indices, optional CSV artifact. */
	private static void registerRandomSample(FlowEngine engine) {
		engine.registerExecutor("random-sample", (node, ctx) -> {
			int rows = Integer.parseInt(String.valueOf(node.params().getOrDefault("rows", "1")));
			if (!(ctx.soleInput() instanceof tech.tablesaw.api.Table table)) {
				throw new IllegalStateException("random-sample node '" + node.id() + "' needs a table upstream");
			}
			int total = table.rowCount();
			int take = Math.min(rows, total);
			int[] index = new int[total];
			for (int i = 0; i < total; i++) {
				index[i] = i;
			}
			java.util.Random rng = ctx.newRandom();
			for (int i = 0; i < take; i++) {
				int j = i + rng.nextInt(total - i);
				int tmp = index[i];
				index[i] = index[j];
				index[j] = tmp;
			}
			int[] chosen = java.util.Arrays.copyOf(index, take);
			java.util.Arrays.sort(chosen);
			tech.tablesaw.api.Table out = table.where(tech.tablesaw.selection.Selection.with(chosen));
			Object outName = node.params().get("out");
			if (outName != null) {
				out.write().csv(ctx.workDir().resolve(String.valueOf(outName)).toFile());
			}
			return out;
		});
	}
}
