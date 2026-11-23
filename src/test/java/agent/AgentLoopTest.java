package agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import core.engine.FlowEngine;
import core.engine.GateState;
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
 * The loop is provider-agnostic and fully offline: a deterministic fake drives proposal,
 * clarification, refusal-reporting, and degradation legs; the OpenAI-compatible adapter is
 * verified for request shape and response parsing without any network. Switching providers
 * is configuration - same loop, same grounding text.
 */
class AgentLoopTest {

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

	private AgentLoop loop(Provider provider, FlowEngine engine, AgentDrafts drafts) {
		ToolRegistry registry = new ToolRegistry();
		AgentTools.install(registry, engine, drafts, workDir,
				() -> new core.model.RequirementThread("thread-1", List.of()),
				() -> FlowGraphIds.of(engine));
		return new AgentLoop(provider, registry, "budget-model",
				() -> Grounding.assembleOrder(engine, new core.model.RequirementThread("thread-1", List.of()),
						workDir, List.of("data.csv"), engine.runLogReplay()),
				4000);
	}

	@Test
	void proposalStagesFromToolCallsWithoutTouchingTheFlow() throws Exception {
		FlowEngine engine = builtEngine();
		AgentDrafts drafts = new AgentDrafts(engine);
		String hashBefore = engine.document().contentHash();
		FakeProvider fake = new FakeProvider(List.of(
				Provider.Response.text("").withToolCalls(new Provider.ToolCall("get_registry_digest", Map.of())),
				Provider.Response.text("").withToolCalls(new Provider.ToolCall("propose_flow", Map.of(
						"op", "set-param", "nodeId", "forest-1", "param", "trees", "value", 40,
						"rationale", "smoother residuals",
						"citations", Map.of("forest-1.trees", "digest:random-forest:param:trees"))))));
		AgentLoop.Outcome outcome = loop(fake, engine, drafts).run("make the forest smoother");

		assertEquals(AgentLoop.Kind.PROPOSAL_STAGED, outcome.kind());
		assertTrue(drafts.stagedCards().contains(outcome.cardId()), "the card waits for the human");
		assertEquals(hashBefore, engine.document().contentHash(), "the flow is untouched pre-confirm");
		assertEquals(GateState.CONFIRMED_RUN, engine.gateState());
		assertTrue(fake.seenRequests().get(0).turns().stream().anyMatch(t -> t.text().contains("seedAware")),
				"the grounding traveled in the request");
	}

	@Test
	void clarificationEndsTheTurnWithAQuestion() throws Exception {
		FlowEngine engine = builtEngine();
		FakeProvider fake = new FakeProvider(List.of(
				Provider.Response.text("Which measure should the forest target - gradrate or attendance?")));
		AgentLoop.Outcome outcome = loop(fake, engine, new AgentDrafts(engine)).run("improve the model");
		assertEquals(AgentLoop.Kind.CLARIFICATION, outcome.kind());
		assertTrue(outcome.message().contains("Which measure"), "the question reaches the chat");
	}

	@Test
	void toolRefusalsFlowBackIntoTheConversation() throws Exception {
		FlowEngine engine = builtEngine();
		FakeProvider fake = new FakeProvider(List.of(
				Provider.Response.text("").withToolCalls(new Provider.ToolCall("propose_flow", Map.of(
						"op", "set-param", "nodeId", "forest-1", "param", "trees", "value", 40,
						"rationale", "no citations", "citations", Map.of("forest-1.trees", "digest:made-up")))),
				Provider.Response.text("I cannot verify that change (digest:made-up is not grounded); "
						+ "which parameter did you mean?")));
		AgentLoop.Outcome outcome = loop(fake, engine, new AgentDrafts(engine)).run("turn it up");
		assertEquals(AgentLoop.Kind.CLARIFICATION, outcome.kind());
		assertTrue(outcome.message().contains("made-up"), "the refusal reason reaches the user");
	}

	@Test
	void aFailingProviderDegradesWithoutCrashing() throws Exception {
		FlowEngine engine = builtEngine();
		Provider failing = request -> {
			throw new IllegalStateException("connection refused");
		};
		AgentLoop.Outcome outcome = loop(failing, engine, new AgentDrafts(engine)).run("anything");
		assertEquals(AgentLoop.Kind.DEGRADED, outcome.kind());
		assertTrue(outcome.message().contains("agent unreachable"));
	}

	@Test
	void openAiCompatRequestShapeAndResponseParsing() throws Exception {
		FlowEngine engine = builtEngine();
		AgentLoop loop = loop(new FakeProvider(List.of(Provider.Response.text("ok"))), engine,
				new AgentDrafts(engine));
		List<Map<String, Object>> tools = loop.toolsForShapeTest();
		List<Provider.Turn> turns = List.of(
				Provider.Turn.of("system", "grounding text"),
				Provider.Turn.of("user", "make the forest smoother"));
		String body = OpenAiCompatProvider.buildRequestBody(new Provider.Request("budget-model", turns, tools));
		assertTrue(body.contains("\"model\" : \"budget-model\"") || body.contains("\"model\":\"budget-model\""));
		assertTrue(body.contains("\"role\" : \"system\"") || body.contains("\"role\":\"system\""));
		assertTrue(body.contains("get_registry_digest") || body.contains("propose_flow"),
				"the tool specs travel as functions");

		Provider.Response parsed = OpenAiCompatProvider.parseResponse("""
				{"choices":[{"message":{"content":null,"tool_calls":[
					{"type":"function","function":{"name":"propose_flow",
					 "arguments":"{\\"op\\":\\"set-param\\",\\"value\\":40}"}}]}}]}""");
		assertEquals("propose_flow", parsed.toolCalls().get(0).name());
		assertEquals(40, parsed.toolCalls().get(0).args().get("value"));
	}

	@Test
	void sameLoopSameGroundingAcrossProviderConfigs() throws Exception {
		FlowEngine engine = builtEngine();
		AgentDrafts drafts = new AgentDrafts(engine);
		ToolRegistry registry = new ToolRegistry();
		AgentTools.install(registry, engine, drafts, workDir,
				() -> new core.model.RequirementThread("thread-1", List.of()), () -> FlowGraphIds.of(engine));
		List<Grounding.Source> order = Grounding.assembleOrder(engine,
				new core.model.RequirementThread("thread-1", List.of()), workDir, List.of("data.csv"),
				engine.runLogReplay());
		String groundingFake = Grounding.assemble(4000, order).text();

		AgentConfig fakeConfig = AgentConfig.offlineFake("budget-model");
		assertEquals("fake", fakeConfig.provider());
		FakeProvider viaConfig = (FakeProvider) fakeConfig.provider(
				List.of(Provider.Response.text("hello")));
		AgentLoop configured = new AgentLoop(viaConfig, registry, fakeConfig.model(),
				() -> order, 4000);
		AgentLoop.Outcome outcome = configured.run("ping");
		assertEquals(AgentLoop.Kind.CLARIFICATION, outcome.kind());
		String groundingSent = viaConfig.seenRequests().get(0).turns().get(2).text();
		assertEquals(groundingFake, groundingSent,
				"the prompt is the grounding either way - switch is config only");
		String body = OpenAiCompatProvider.buildRequestBody(new Provider.Request("budget-model",
				viaConfig.seenRequests().get(0).turns(), registry.schemas()));
		assertTrue(body.contains("graduation") || body.contains("gradrate") || body.contains("digest:"),
				"the real adapter would send the identical grounded prompt");
	}
}
