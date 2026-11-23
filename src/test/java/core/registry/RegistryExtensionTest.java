package core.registry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import core.engine.FlowEngine;
import core.engine.RunConfig;
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
import tech.tablesaw.api.Table;

/**
 * The extension proof: a node type joins as pure data (descriptor + executor) through the public
 * registration API — no engine-core change — then shows up in the digest and executes in a
 * flow. This test lives in the registry package, outside the engine's internals.
 */
class RegistryExtensionTest {

	@TempDir
	Path workDir;

	@Test
	void extensionJoinsAsPureDataAndExecutesInAFlow() throws Exception {
		NodeRegistry registry = NodeRegistry.standard();
		int before = registry.descriptors().size();
		registry.register(
				new NodeDescriptor("token-count", "test", "count the rows of the upstream table",
						List.of(new ParamSpec("label", "string", "tag echoed into the result", false)), 1, 1, false),
				(node, ctx) -> {
					Object upstream = ctx.soleInput();
					Table table = (Table) upstream;
					return node.params().getOrDefault("label", "") + ":" + table.rowCount();
				});
		assertEquals(before + 1, registry.descriptors().size(), "registration is additive");
		assertTrue(registry.digest().contains("token-count (test): count the rows of the upstream table"),
				"the descriptor feeds the digest exactly as shipped nodes do");

		Files.writeString(workDir.resolve("mini.csv"), "a,b\n1,2\n3,4\n");
		FlowDocument doc = new FlowDocument("extension-proof", 1,
				List.of(
						new Node("import-csv-1", "import-csv", Map.of("path", "mini.csv")),
						new Node("count-1", "token-count", Map.of("label", "rows"))),
				List.of(new Wire("import-csv-1", "count-1")),
				1L,
				new Provenance("hand-built", "harry", "2026-11-23T12:00:00Z"), "thread-1", List.of());
		FlowEngine engine = new FlowEngine(RunConfig.seeded(1L).workDir(workDir), registry);
		engine.propose(doc, "card-1", "buddy");
		engine.userConfirmBuild("card-1", "harry");
		engine.materialize("harry");
		engine.userConfirmRun("harry");
		assertEquals(RunState.COMPLETED, engine.startRun("harry").await().state(),
				"the extension executes end-to-end through the gated path");
		assertTrue(engine.runLogReplay().stream().anyMatch(e -> e.type().equals("node-green")
				&& e.nodeId().equals("count-1")), "the extension node runs green");
	}
}
