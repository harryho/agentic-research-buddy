package agent;

import core.engine.FlowEngine;
import core.engine.RunEvent;
import core.model.FlowDocument;
import core.model.RequirementThread;
import core.registry.NodeDescriptor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Grounding assembly: the four evidence sources the agent composes from, in a fixed
 * retention order - requirement thread, registry digest (with output columns and
 * seed-awareness), dataset schemas with sample rows leading, run-log tail, exemplars last.
 * Fixed char budget, deterministic content: the same inputs assemble byte-for-byte the same
 * grounding, and every source carries a citable id.
 */
public final class Grounding {

	public record Source(String id, String text) {
	}

	public record Assembled(String text, List<Source> sources) {
	}

	private Grounding() {
	}

	/** Includes sources in their given (priority) order under the budget; overflow is dropped. */
	public static Assembled assemble(int charBudget, List<Source> orderedSources) {
		StringBuilder text = new StringBuilder();
		List<Source> included = new ArrayList<>();
		int used = 0;
		for (Source source : orderedSources) {
			String block = "[" + source.id() + "] " + source.text();
			if (used + block.length() > charBudget) {
				break;
			}
			text.append(block).append("\n\n");
			included.add(source);
			used += block.length() + 2;
		}
		return new Assembled(text.toString(), List.copyOf(included));
	}

	public static List<Source> threadSources(RequirementThread thread) {
		List<Source> sources = new ArrayList<>();
		List<RequirementThread.Entry> entries = thread.entries();
		for (int i = 0; i < entries.size(); i++) {
			sources.add(new Source("thread:" + i, entries.get(i).author() + ": " + entries.get(i).text()));
		}
		return sources;
	}

	/** Per-type digest entries: typed params, seed-awareness, output columns, requirements. */
	public static List<Source> digestSources(FlowEngine engine) {
		List<Source> sources = new ArrayList<>();
		for (NodeDescriptor d : engine.registryView().descriptors()) {
			core.engine.ColumnShapes.OutputContract contract = core.engine.ColumnShapes.contract(d.type());
			StringBuilder text = new StringBuilder();
			text.append(d.purpose());
			text.append(" | family: ").append(d.family());
			text.append(" | inputs: ").append(d.inputs()).append(", outputs: ").append(d.outputs());
			text.append(" | seedAware: ").append(d.seedAware());
			text.append(" | outputColumns kind: ").append(contract.kind());
			text.append(" | adds: ").append(contract.adds());
			text.append(" | requires: ").append(contract.requires());
			sources.add(new Source("digest:" + d.type(), text.toString()));
			for (core.registry.ParamSpec p : d.params()) {
				sources.add(new Source("digest:" + d.type() + ":param:" + p.name(),
						p.type() + (p.required() ? " required" : " optional") + " - " + p.constraint()));
			}
		}
		return sources;
	}

	/** Sample rows first (the concrete evidence), then one citable entry per column. */
	public static List<Source> schemaSources(Path workDir, List<String> paths) {
		List<Source> sources = new ArrayList<>();
		for (String path : paths) {
			Path csv;
			try {
				csv = core.persist.Names.safeRelative(workDir, path);
			} catch (IllegalArgumentException e) {
				sources.add(new Source("schema:" + path + ":refused",
						"path refused by the workspace gate; no schema evidence"));
				continue;
			}
			if (!Files.exists(csv)) {
				continue;
			}
			try {
				List<String> lines = Files.readAllLines(csv);
				if (lines.isEmpty()) {
					continue;
				}
				List<String> columns = new ArrayList<>();
				for (String column : lines.get(0).split(",")) {
					columns.add(column.trim());
				}
				List<String> rows = lines.subList(1, Math.min(lines.size(), 4));
				sources.add(new Source("schema:" + path + ":rows", String.join(" ; ", rows)));
				for (String column : columns) {
					sources.add(new Source("schema:" + path + ":" + column, "column " + column));
				}
			} catch (Exception e) {
				sources.add(new Source("schema:" + path + ":error", "unreadable: " + e.getMessage()));
			}
		}
		return sources;
	}

	/** The newest events, bounded, newest last. */
	public static List<Source> logTailSources(List<RunEvent> events, int tail) {
		List<RunEvent> newest = events.subList(Math.max(0, events.size() - tail), events.size());
		List<Source> sources = new ArrayList<>();
		for (RunEvent event : newest) {
			String text = event.level() + " " + event.type()
					+ (event.nodeId().isEmpty() ? "" : " @" + event.nodeId())
					+ (event.message().isEmpty() ? "" : ": " + event.message());
			sources.add(new Source("log:" + event.seq(), text));
		}
		return sources;
	}

	/** The shipped demo flows as composition exemplars - always lowest retention priority. */
	public static List<Source> exemplars() {
		List<Source> sources = new ArrayList<>();
		Map<String, String> patterns = new LinkedHashMap<>();
		patterns.put("regression", "import-csv -> clean-drop-missing -> random-forest -> eval-regression "
				+ "-> residuals-plot -> prediction-table (scatter fan-out for the raw columns)");
		patterns.put("classification", "import-csv -> clean-drop-missing -> knn-classify or svm-classify "
				+ "-> eval-classification -> prediction-table");
		patterns.put("coefficients", "import-csv -> clean-drop-missing -> ols-regression on its own wire; "
				+ "eval nodes must sit after a scorer, never after OLS");
		for (Map.Entry<String, String> e : patterns.entrySet()) {
			sources.add(new Source("exemplar:" + e.getKey(), e.getValue()));
		}
		return sources;
	}

	/** Convenience: the full four-source order for an open flow. */
	public static List<Source> assembleOrder(FlowEngine engine, RequirementThread thread, Path workDir,
			List<String> datasetPaths, List<RunEvent> logTail) {
		List<Source> ordered = new ArrayList<>();
		ordered.addAll(threadSources(thread));
		ordered.addAll(digestSources(engine));
		ordered.addAll(schemaSources(workDir, datasetPaths));
		ordered.addAll(logTailSources(logTail, 20));
		ordered.addAll(exemplars());
		return ordered;
	}
}
