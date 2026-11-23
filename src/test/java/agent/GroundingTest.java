package agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import core.engine.FlowEngine;
import core.engine.RunEvent;
import core.model.FlowDocument;
import core.model.Node;
import core.model.Provenance;
import core.model.RequirementThread;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Grounding assembly: the four sources in retention order (thread, digest, dataset schema
 * with sample rows first, run-log tail, exemplars last), deterministic under a fixed char
 * budget. Citability: every proposed field must cite a source the agent actually saw -
 * unverifiable proposals are refused with the reason.
 */
class GroundingTest {

	@TempDir
	Path workDir;

	@Test
	void retentionOrderHoldsUnderABudget() {
		Grounding.Source big = new Grounding.Source("log:9", "x".repeat(5000));
		List<Grounding.Source> ordered = List.of(
				new Grounding.Source("thread:0", "Which factors predict graduation rate?"),
				new Grounding.Source("digest:eval-regression", "requires predicted"),
				new Grounding.Source("schema:data.csv:gradrate", "sample rows"),
				big,
				new Grounding.Source("exemplar:1", "a golden flow pattern"));
		Grounding.Assembled assembled = Grounding.assemble(600, ordered);
		assertTrue(assembled.text().contains("graduation rate"), "thread survives first");
		assertTrue(assembled.text().contains("requires predicted"), "digest survives second");
		assertTrue(assembled.text().contains("sample rows"), "schema survives third");
		assertTrue(!assembled.text().contains("golden flow pattern"), "exemplars drop first under budget");
		assertEquals(List.of("thread:0", "digest:eval-regression", "schema:data.csv:gradrate"),
				assembled.sources().stream().map(Grounding.Source::id).toList(),
				"the assembled source list is deterministic and citable");
	}

	@Test
	void assemblyIsDeterministic() {
		List<Grounding.Source> ordered = List.of(
				new Grounding.Source("thread:0", "same question"),
				new Grounding.Source("digest:ols-regression", "outputs term, coefficient"));
		assertEquals(Grounding.assemble(500, ordered).text(), Grounding.assemble(500, ordered).text(),
				"same inputs, same grounding - byte for byte");
	}

	@Test
	void digestSourcesCarryColumnsAndSeedAwareness() throws Exception {
		Files.writeString(workDir.resolve("data.csv"), "hours,gradrate\n2,50\n");
		FlowEngine engine = new FlowEngine(core.engine.RunConfig.seeded(7L).workDir(workDir));
		List<Grounding.Source> digest = Grounding.digestSources(engine);
		String text = String.join("\n", digest.stream().map(Grounding.Source::text).toList());
		Set<String> ids = Set.copyOf(digest.stream().map(Grounding.Source::id).toList());
		assertTrue(ids.contains("digest:random-forest"), "a per-type entry exists");
		assertTrue(ids.contains("digest:random-forest:param:trees"), "typed params are citable");
		assertTrue(text.contains("seedAware: true"), "reproducibility metadata travels");
		assertTrue(text.contains("requires: [predicted]"), "eval composition requirements travel");
		assertTrue(text.contains("adds: [predicted]"), "scorer output columns travel");
		assertTrue(text.contains("kind: replace") && text.contains("term, coefficient"),
				"OLS declares its summary shape - eval cannot legally follow it");
	}

	@Test
	void schemaSourcesLeadWithSampleRows() throws Exception {
		Files.writeString(workDir.resolve("data.csv"), "hours,gradrate\n2,50\n4,60\n7,80\n");
		List<Grounding.Source> schema = Grounding.schemaSources(workDir, List.of("data.csv"));
		assertTrue(schema.get(0).id().equals("schema:data.csv:rows"), "sample rows first");
		assertTrue(schema.get(0).text().contains("2,50"), "the rows are real");
		assertTrue(schema.stream().anyMatch(s -> s.id().equals("schema:data.csv:hours")),
				"each column is citable");
	}

	@Test
	void citationsVerifyAgainstSeenSourcesOrRefuse() throws Exception {
		Files.writeString(workDir.resolve("data.csv"), "hours,gradrate\n2,50\n");
		FlowEngine engine = new FlowEngine(core.engine.RunConfig.seeded(7L).workDir(workDir));
		Set<String> seen = Set.copyOf(Grounding.digestSources(engine).stream()
				.map(Grounding.Source::id).toList());

		assertEquals(List.of(), Citations.verify(Map.of("forest-1.trees", "digest:random-forest:param:trees"), seen));
		List<String> refusals = Citations.verify(Map.of("forest-1.trees", "digest:made-up:type"), seen);
		assertEquals(1, refusals.size());
		assertTrue(refusals.get(0).contains("made-up"), "the refusal names the unverifiable source");
		assertTrue(Citations.verify(Map.of(), seen).size() == 1, "no citations at all is unverifiable");
	}

	@Test
	void theToolRefusesUnverifiableProposals() throws Exception {
		Files.writeString(workDir.resolve("data.csv"), "hours,gradrate\n2,50\n");
		FlowEngine engine = new FlowEngine(core.engine.RunConfig.seeded(7L).workDir(workDir));
		engine.propose(new FlowDocument("f", 1,
				List.of(new Node("forest-1", "random-forest", Map.of("target", "g", "features", "hours"))),
				List.of(), 7L, new Provenance("hand-built", "harry", "2026-11-23T12:00:00Z"), "t", List.of()),
				"c1", "buddy");
		engine.userConfirmBuild("c1", "harry");
		AgentDrafts drafts = new AgentDrafts(engine);
		ToolRegistry registry = new ToolRegistry();
		Set<String> seen = Set.copyOf(Grounding.digestSources(engine).stream()
				.map(Grounding.Source::id).toList());
		AgentTools.install(registry, engine, drafts, workDir, () -> new RequirementThread("t", List.of()), () -> seen);

		ToolResult unverifiable = registry.get("propose_flow").execute(java.util.Map.of(
				"op", "set-param", "nodeId", "forest-1", "param", "trees", "value", 40,
				"rationale", "guess", "citations", Map.of("forest-1.trees", "digest:made-up:type")));
		assertTrue(!unverifiable.ok(), "an unverifiable proposal is refused");
		assertTrue(unverifiable.refusalReason().contains("made-up"));

		ToolResult verifiable = registry.get("propose_flow").execute(java.util.Map.of(
				"op", "set-param", "nodeId", "forest-1", "param", "trees", "value", 40,
				"rationale", "grounded in the digest", "citations",
				Map.of("forest-1.trees", "digest:random-forest:param:trees")));
		assertTrue(verifiable.ok(), "a citable proposal stages");
	}

	@Test
	void traversalPathsAreRefusedBeforeAnyRead() throws Exception {
		Path outside = workDir.resolve("..").resolve("b5-secret.csv");
		Files.writeString(outside, "ssn,balance\n1,2\n");
		try {
			FlowEngine engine = new FlowEngine(core.engine.RunConfig.seeded(7L).workDir(workDir));
			ToolRegistry registry = new ToolRegistry();
			AgentTools.install(registry, engine, new AgentDrafts(engine), workDir,
					() -> new RequirementThread("t", java.util.List.of()), java.util.Set::of);

			ToolResult refused = registry.get("get_dataset_schema").execute(java.util.Map.of("path", "../b5-secret.csv"));
			assertTrue(!refused.ok(), "a traversal path is refused at the tool");
			assertTrue(refused.refusalReason().contains("refused"));

			java.util.List<Grounding.Source> sources = Grounding.schemaSources(workDir, List.of("../b5-secret.csv"));
			assertTrue(sources.stream().noneMatch(source -> source.text().contains("ssn")),
					"no content from outside the workspace ever enters the grounding");
			assertTrue(sources.get(0).text().contains("refused"), "the refusal is honest about the evidence gap");
		} finally {
			Files.deleteIfExists(outside);
		}
	}

	@Test
	void logTailSourcesAreBoundedAndTagged() {
		List<RunEvent> events = new java.util.ArrayList<>();
		for (long seq = 1; seq <= 30; seq++) {
			events.add(new RunEvent("2026-11-23T12:00:00Z", seq, "info", "n" + seq, "tick", ""));
		}
		List<Grounding.Source> tail = Grounding.logTailSources(events, 5);
		assertEquals(5, tail.size(), "the tail is bounded");
		assertEquals("log:30", tail.get(tail.size() - 1).id(), "newest last");
	}
}
