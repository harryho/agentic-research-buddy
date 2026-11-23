package agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import core.engine.GateState;
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

/**
 * The agent's tool surface: typed tools over the engine's public seams, staged drafts that
 * never touch the document until the human confirms, and tool-level composition legality
 * (eval after OLS cannot even be staged). The staged-write proof: pre-confirm the hash is
 * untouched - mid-run included; on confirm the write lands attributed like a human edit.
 */
class StagedWritesTest {

	@TempDir
	Path workDir;

	private Studio work() throws Exception {
		Files.writeString(workDir.resolve("data.csv"), "hours,gradrate\n2,50\n4,60\n");
		return new Studio();
	}

	/** One object wiring the agent surface to a real gated engine. */
	class Studio {

		final core.engine.FlowEngine engine = new core.engine.FlowEngine(
				core.engine.RunConfig.seeded(7L).workDir(workDir));

		final AgentDrafts drafts = new AgentDrafts(engine);

		final ToolRegistry registry = new ToolRegistry();

		java.util.function.Supplier<java.util.Set<String>> citableSources() {
			return () -> java.util.Set.copyOf(FlowGraphIds.of(engine));
		}

		FlowDocument doc() {
			return engine.document();
		}

		void build(FlowDocument doc) {
			engine.propose(doc, "card-1", "buddy");
			engine.userConfirmBuild("card-1", "harry");
			engine.materialize("harry");
			engine.userConfirmRun("harry");
		}
	}

	private FlowDocument scored() {
		return new FlowDocument("scored", 1,
				List.of(
						new Node("import-1", "import-csv", Map.of("path", "data.csv")),
						new Node("forest-1", "random-forest", Map.of("target", "gradrate", "features", "hours")),
						new Node("eval-1", "eval-regression", Map.of("actual", "gradrate", "predicted", "predicted"))),
				List.of(new Wire("import-1", "forest-1"), new Wire("forest-1", "eval-1")),
				7L, new Provenance("hand-built", "harry", "2026-11-23T12:00:00Z"), "thread-1", List.of());
	}

	@Test
	void registryCarriesTheSixTypedTools() {
		Studio s = new Studio();
		AgentTools.install(s.registry, s.engine, s.drafts, workDir, () -> new core.model.RequirementThread("thread-1", java.util.List.of()), s.citableSources());
		for (String name : List.of("get_registry_digest", "get_dataset_schema", "read_run_log",
				"get_requirement_thread", "propose_flow", "propose_diagnosis")) {
			assertNotNull(s.registry.get(name), name + " is installed");
			assertFalse(s.registry.schemas().isEmpty());
		}
		assertEquals(6, s.registry.tools().size());
		assertTrue(s.registry.schemas().stream().allMatch(schema -> schema.get("type").equals("function")
				&& schema.get("function") instanceof Map<?, ?> function
				&& function.get("name") != null
				&& function.get("description") != null
				&& function.get("parameters") != null),
				"every schema is a provider function spec");
	}

	@Test
	void stagedProposalNeverTouchesTheFlowUntilConfirm() throws Exception {
		Studio s = work();
		s.build(scored());
		AgentTools.install(s.registry, s.engine, s.drafts, workDir, () -> new core.model.RequirementThread("thread-1", java.util.List.of()), s.citableSources());
		String hashBefore = s.doc().contentHash();

		ToolResult staged = s.registry.get("propose_flow").execute(Map.of(
				"op", "set-param", "nodeId", "forest-1", "param", "trees", "value", 40,
				"rationale", "more stumps for smoother residuals",
				"citations", Map.of("forest-1.trees", "digest:random-forest:param:trees")));

		assertTrue(staged.ok(), "the edit composes legally");
		assertEquals(hashBefore, s.doc().contentHash(), "pre-confirm: the flow is untouched");
		assertEquals(GateState.CONFIRMED_RUN, s.engine.gateState());
		assertTrue(s.drafts.stagedCards().contains(staged.data()), "the edit waits as a card");

		FlowDocument after = s.drafts.apply(String.valueOf(staged.data()));
		assertEquals(GateState.IDLE, s.engine.gateState(), "confirm is the edit path: IDLE");
		assertTrue(!after.contentHash().equals(hashBefore), "the write landed on confirm");
		assertTrue(s.engine.gateEvents().stream().anyMatch(e -> e.kind().equals("edit")
				&& e.actor().equals("agent")), "the landed edit is agent-attributed, like human edits");
		assertEquals(40, s.doc().nodes().stream().filter(n -> n.id().equals("forest-1"))
				.findFirst().orElseThrow().params().get("trees"));
	}

	@Test
	void stagingDuringARunLeavesTheFlowUntouched() throws Exception {
		Studio s = work();
		s.build(scored());
		AgentTools.install(s.registry, s.engine, s.drafts, workDir, () -> new core.model.RequirementThread("thread-1", java.util.List.of()), s.citableSources());
		String hashBefore = s.doc().contentHash();

		core.engine.RunHandle handle = s.engine.startRun("harry");
		ToolResult staged = s.registry.get("propose_flow").execute(Map.of(
				"op", "set-param", "nodeId", "forest-1", "param", "trees", "value", 40,
				"rationale", "mid-run observation", "citations",
				Map.of("forest-1.trees", "digest:random-forest:param:trees")));
		handle.await();
		assertEquals(RunState.COMPLETED, handle.await().state());
		assertTrue(staged.ok());
		assertEquals(hashBefore, s.doc().contentHash(), "mid-run staging leaves the running flow alone");
	}

	@Test
	void staleDraftIsRefusedWhenTheFlowMovedUnderIt() throws Exception {
		Studio s = work();
		s.build(scored());
		AgentTools.install(s.registry, s.engine, s.drafts, workDir, () -> new core.model.RequirementThread("thread-1", java.util.List.of()), s.citableSources());
		ToolResult staged = s.registry.get("propose_flow").execute(Map.of(
				"op", "set-param", "nodeId", "forest-1", "param", "trees", "value", 40,
				"rationale", "smoother", "citations",
				Map.of("forest-1.trees", "digest:random-forest:param:trees")));
		String cardId = String.valueOf(staged.data());

		core.model.FlowDocument humanEdited = core.model.FlowEdits.setParameter(
				s.engine.document(), "forest-1", "trees", 5);
		s.engine.applyEdit(humanEdited, "user");
		String hashAfterHumanEdit = s.engine.document().contentHash();

		org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, () -> s.drafts.apply(cardId),
				"applying a stale card would silently revert the human edit");
		assertTrue(s.drafts.stagedCards().contains(cardId), "the stale card stays staged for re-stage or decline");
		assertEquals(hashAfterHumanEdit, s.engine.document().contentHash(),
				"the refused apply left the human edit intact");
	}

	@Test
	void evalAfterOlsIsUncomposableAtTheToolLevel() throws Exception {
		Studio s = work();
		FlowDocument olsFed = new FlowDocument("scored", 1,
				List.of(
						new Node("import-1", "import-csv", Map.of("path", "data.csv")),
						new Node("ols-1", "ols-regression", Map.of("target", "gradrate", "features", "hours")),
						new Node("forest-1", "random-forest", Map.of("target", "gradrate", "features", "hours")),
						new Node("eval-1", "eval-regression", Map.of("actual", "gradrate", "predicted", "predicted"))),
				List.of(new Wire("import-1", "ols-1"), new Wire("import-1", "forest-1"),
						new Wire("forest-1", "eval-1")),
				7L, new Provenance("hand-built", "harry", "2026-11-23T12:00:00Z"), "thread-1", List.of());
		s.build(olsFed);
		AgentTools.install(s.registry, s.engine, s.drafts, workDir, () -> new core.model.RequirementThread("thread-1", java.util.List.of()), s.citableSources());

		ToolResult refused = s.registry.get("propose_flow").execute(Map.of(
				"op", "rewire", "from", "ols-1", "to", "eval-1", "rationale", "tighter model",
				"citations", Map.of()));
		assertFalse(refused.ok(), "the composition is illegal");
		assertTrue(refused.refusalReason().contains("predicted"),
				"the refusal names the missing column: " + refused.refusalReason());
		assertEquals(s.engine.document().contentHash(), s.doc().contentHash());
		assertTrue(s.drafts.stagedCards().isEmpty(), "nothing staged from an illegal composition");
	}

	@Test
	void diagnosisToolRequiresAFixOrAnExplicitNoFix() throws Exception {
		Studio s = work();
		s.build(scored());
		AgentTools.install(s.registry, s.engine, s.drafts, workDir, () -> new core.model.RequirementThread("thread-1", java.util.List.of()), s.citableSources());

		ToolResult neither = s.registry.get("propose_diagnosis").execute(Map.of(
				"nodeId", "eval-1", "errorText", "column missing"));
		assertFalse(neither.ok(), "diagnosis rule: no silent diagnosis - fix or explicit no-fix statement");

		ToolResult noFix = s.registry.get("propose_diagnosis").execute(Map.of(
				"nodeId", "eval-1", "errorText", "column missing",
				"noFixStatement", "the upstream never produces the column; re-plan the flow instead"));
		assertTrue(noFix.ok(), "an explicit no-fix statement is a legal diagnosis card");
		assertTrue(s.drafts.stagedCards().contains(noFix.data()));
	}
}
