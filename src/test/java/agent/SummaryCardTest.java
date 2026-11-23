package agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import core.engine.FlowEngine;
import core.engine.GateState;
import core.model.FlowDocument;
import core.model.Node;
import core.model.Provenance;
import core.model.RequirementThread;
import core.model.Wire;
import core.persist.Library;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The loop's product side: staged proposals render as confirm cards with per-field
 * provenance; diagnosis cards obey the diagnosis rule (concrete fix or explicit no-fix); the
 * agent probe degrades honestly; the requirement thread persists with the flow and a
 * re-confirmed summary rebuilds it and bumps the version.
 */
class SummaryCardTest {

	@TempDir
	Path workDir;

	private FlowEngine builtEngine() throws Exception {
		Files.writeString(workDir.resolve("data.csv"), "hours,gradrate\n2,50\n4,60\n");
		FlowEngine engine = new FlowEngine(core.engine.RunConfig.seeded(7L).workDir(workDir));
		engine.propose(new FlowDocument("scored", 1,
				List.of(
						new Node("import-1", "import-csv", Map.of("path", "data.csv")),
						new Node("forest-1", "random-forest", Map.of("target", "gradrate", "features", "hours")),
						new Node("eval-1", "eval-regression", Map.of("actual", "gradrate", "predicted", "predicted"))),
				List.of(new Wire("import-1", "forest-1"), new Wire("forest-1", "eval-1")),
				7L, new Provenance("hand-built", "harry", "2026-11-23T12:00:00Z"), "thread-1", List.of()),
				"c1", "buddy");
		engine.userConfirmBuild("c1", "harry");
		engine.materialize("harry");
		engine.userConfirmRun("harry");
		return engine;
	}

	@Test
	void proposalCardCarriesChangeRationaleAndProvenance() throws Exception {
		FlowEngine engine = builtEngine();
		AgentDrafts drafts = new AgentDrafts(engine);
		ToolRegistry registry = new ToolRegistry();
		AgentTools.install(registry, engine, drafts, workDir,
				() -> new core.model.RequirementThread("t", List.of()), () -> FlowGraphIds.of(engine));
		ToolResult staged = registry.get("propose_flow").execute(Map.of(
				"op", "set-param", "nodeId", "forest-1", "param", "trees", "value", 40,
				"rationale", "smoother residuals", "citations",
				Map.of("forest-1.trees", "digest:random-forest:param:trees")));
		String cardId = String.valueOf(staged.data());

		String card = ProposalCards.summarize(engine.document(), drafts.get(cardId));
		assertTrue(card.contains("forest-1: trees = 40"), "the card states the change: " + card);
		assertTrue(card.contains("smoother residuals"), "the card states why");
		assertTrue(card.contains("digest:random-forest:param:trees"), "the card shows the provenance");

		FlowDocument after = drafts.apply(cardId);
		assertEquals(GateState.IDLE, engine.gateState());
		assertEquals(40, after.nodes().stream().filter(n -> n.id().equals("forest-1"))
				.findFirst().orElseThrow().params().get("trees"));
	}

	@Test
	void diagnosisCardsObeyTheCapLaw() throws Exception {
		FlowEngine engine = builtEngine();
		AgentDrafts drafts = new AgentDrafts(engine);
		ToolRegistry registry = new ToolRegistry();
		AgentTools.install(registry, engine, drafts, workDir,
				() -> new core.model.RequirementThread("t", List.of()), () -> FlowGraphIds.of(engine));

		ToolResult noFix = registry.get("propose_diagnosis").execute(Map.of(
				"nodeId", "eval-1", "errorText", "column missing",
				"noFixStatement", "the upstream never produces the column; re-plan the flow instead"));
		String noFixCard = ProposalCards.summarize(engine.document(), drafts.get(String.valueOf(noFix.data())));
		assertTrue(noFixCard.contains("no fix available: the upstream never produces the column"),
				"the explicit no-fix statement is the card: " + noFixCard);

		ToolResult fixed = registry.get("propose_diagnosis").execute(Map.of(
				"nodeId", "forest-1", "errorText", "too coarse",
				"fixOp", Map.of("op", "set-param", "nodeId", "forest-1", "param", "trees", "value", 64),
				"rationale", "concrete fix"));
		String fixCard = ProposalCards.summarize(engine.document(), drafts.get(String.valueOf(fixed.data())));
		assertTrue(fixCard.contains("proposed fix (confirm to apply)"), "the fix is confirmable: " + fixCard);
		assertTrue(fixCard.contains("forest-1: trees = 64"));
	}

	@Test
	void agentProbeDegradesHonestly() throws Exception {
		FlowEngine engine = builtEngine();
		Provider unreachable = request -> {
			throw new IllegalStateException("connection refused");
		};
		AgentLoop loop = new AgentLoop(unreachable, new ToolRegistry(), "budget-model",
				() -> List.of(), 100);
		AgentLoop.Outcome outcome = loop.run("hello");
		assertEquals(AgentLoop.Kind.DEGRADED, outcome.kind());
		assertTrue(outcome.message().contains("agent unreachable"),
				"the honest label the UI disables controls with");
		Provider fake = new FakeProvider(List.of(Provider.Response.text("here")));
		assertEquals(AgentLoop.Kind.CLARIFICATION,
				new AgentLoop(fake, new ToolRegistry(), "m", () -> List.of(), 100).run("hello").kind(),
				"the fake config is reachable offline");
	}

	@Test
	void reconfirmedSummaryRebuildsThreadAndBumpsVersion() throws Exception {
		Files.writeString(workDir.resolve("data.csv"), "hours,gradrate\n2,50\n");
		Path workspace = workDir.resolve("workspace");
		Library library = new Library(workspace);
		library.createProject("thesis");
		FlowDocument doc = library.copyDemo("iris-knn", "thesis");
		RequirementThread thread = library.openDemo("iris-knn").thread();

		FlowEngine engine = new FlowEngine(core.engine.RunConfig.seeded(7L).workDir(workDir));
		engine.restore(doc);

		SessionThread session = new SessionThread("thread-iris");
		for (RequirementThread.Entry entry : thread.entries()) {
			session.add(entry.author(), entry.text());
		}
		session.add("user", "make k bigger");

		AgentDrafts drafts = new AgentDrafts(engine);
		ToolRegistry registry = new ToolRegistry();
		AgentTools.install(registry, engine, drafts, workDir, session::snapshot, () -> FlowGraphIds.of(engine));
		ToolResult staged = registry.get("propose_flow").execute(Map.of(
				"op", "set-param", "nodeId", "knn-1", "param", "k", "value", 7,
				"rationale", "smoother vote", "citations",
				Map.of("knn-1.k", "digest:knn-classify:param:k")));
		assertEquals(GateState.IDLE, engine.gateState(), "restore adopts the persisted history");

		FlowDocument edited = drafts.apply(String.valueOf(staged.data()));
		session.addReconfirm("k=7");
		FlowDocument bumped = library.saveVersion("thesis", edited, session.snapshot());
		assertEquals(List.of(1, 2), library.versions("thesis"), "a changed summary materializes version 2");
		assertEquals(2, bumped.version());
		RequirementThread rebuilt = library.openVersionThread("thesis", 2);
		assertTrue(rebuilt.toMarkdown().contains("summary re-confirmed after edit"),
				"the rebuilt thread carries the re-confirm");
		assertTrue(rebuilt.toMarkdown().contains("make k bigger"), "the user's turn rides the thread");
	}
}
