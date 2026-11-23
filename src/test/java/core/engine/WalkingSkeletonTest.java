package core.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import core.model.FlowDocument;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WalkingSkeletonTest {

	@TempDir
	Path workDir;

	@Test
	void twoNodeFlowRunsHeadlessThroughTheGateAndLogsNodesInOrder() throws Exception {
		Files.writeString(workDir.resolve("mini.csv"), String.join("\n",
				"height,weight",
				"170,65",
				"182,80",
				"155,50",
				"167,60"));

		Path flowJson = workDir.resolve("flow.json");
		Files.writeString(flowJson, """
				{
				  "nodes": [
				    {"id": "import-csv-1", "type": "import-csv", "params": {"path": "mini.csv"}},
				    {"id": "prediction-table-1", "type": "prediction-table", "params": {}}
				  ],
				  "wires": [
				    {"from": "import-csv-1", "to": "prediction-table-1"}
				  ]
				}
				""");

		FlowDocument doc = FlowDocument.fromJson(Files.readString(flowJson));
		FlowEngine engine = new FlowEngine(RunConfig.seeded(1L).workDir(workDir));
		engine.propose(doc, "card-1", "buddy");
		engine.userConfirmBuild("card-1", "harry");
		engine.materialize("harry");
		engine.userConfirmRun("harry");
		RunResult result = engine.startRun("harry").await();

		assertEquals(RunState.COMPLETED, result.state());
		assertEquals("green", result.nodeStatus("import-csv-1"));
		assertEquals("green", result.nodeStatus("prediction-table-1"));

		List<String> greenOrder = engine.runLogReplay().stream()
				.filter(e -> e.type().equals("node-green"))
				.map(RunEvent::nodeId)
				.toList();
		assertEquals(List.of("import-csv-1", "prediction-table-1"), greenOrder);
		assertTrue(engine.runLogReplay().get(0).type().equals("run-started"), "the archive opens with run-started");
	}
}
