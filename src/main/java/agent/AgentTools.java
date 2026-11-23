package agent;

import core.engine.ColumnShapes;
import core.engine.FlowEngine;
import core.engine.RunEvent;
import core.model.FlowDocument;
import core.model.Node;
import core.registry.NodeDescriptor;
import core.registry.ParamSpec;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * The six shipping agent tools. Reads are honest engine/library reads; the two write tools
 * only stage cards in AgentDrafts - the flow document is untouched until the human
 * confirms. propose_flow validates composition with the engine's own column-shape rules,
 * so an eval wired after OLS is refused at the tool level, before any card exists.
 */
public final class AgentTools {

	private AgentTools() {
	}

	public static void install(ToolRegistry registry, FlowEngine engine, AgentDrafts drafts, Path workDir,
			Supplier<core.model.RequirementThread> threadSource, Supplier<java.util.Set<String>> citableSources) {
		registry.register(registryDigest(engine));
		registry.register(datasetSchema(workDir));
		registry.register(runLog(engine));
		registry.register(requirementThread(threadSource));
		registry.register(proposeFlow(engine, drafts, workDir, citableSources));
		registry.register(proposeDiagnosis(engine, drafts));
	}

	private static ToolRegistry.Tool registryDigest(FlowEngine engine) {
		return new ToolRegistry.Tool() {
			@Override
			public String name() {
				return "get_registry_digest";
			}

			@Override
			public String description() {
				return "The node palette with typed parameters, wire arity, seed-awareness, "
						+ "and per-node output columns - the composition contract.";
			}

			@Override
			public Map<String, String> params() {
				return Map.of();
			}

			@Override
			public ToolResult execute(Map<String, Object> args) {
				List<Map<String, Object>> entries = new ArrayList<>();
				for (NodeDescriptor d : engine.registryView().descriptors()) {
					ColumnShapes.OutputContract contract = ColumnShapes.contract(d.type());
					Map<String, Object> entry = new LinkedHashMap<>();
					entry.put("type", d.type());
					entry.put("family", d.family());
					entry.put("purpose", d.purpose());
					entry.put("params", paramList(d));
					entry.put("inputs", d.inputs());
					entry.put("outputs", d.outputs());
					entry.put("seedAware", d.seedAware());
					entry.put("outputColumns", Map.of("kind", contract.kind(), "adds", contract.adds()));
					entry.put("requiresColumns", contract.requires());
					entries.add(entry);
				}
				return ToolResult.ok("node registry digest", entries);
			}
		};
	}

	private static List<Map<String, Object>> paramList(NodeDescriptor d) {
		List<Map<String, Object>> params = new ArrayList<>();
		for (ParamSpec p : d.params()) {
			Map<String, Object> param = new LinkedHashMap<>();
			param.put("name", p.name());
			param.put("type", p.type());
			param.put("required", p.required());
			param.put("constraint", p.constraint());
			params.add(param);
		}
		return params;
	}

	private static ToolRegistry.Tool datasetSchema(Path workDir) {
		return new ToolRegistry.Tool() {
			@Override
			public String name() {
				return "get_dataset_schema";
			}

			@Override
			public String description() {
				return "Columns and the first rows of a CSV dataset in the workspace, for legal wiring and citation.";
			}

			@Override
			public Map<String, String> params() {
				return Map.of("path", "string");
			}

			@Override
			public ToolResult execute(Map<String, Object> args) {
				String path = String.valueOf(args.getOrDefault("path", ""));
				Path csv;
				try {
					csv = core.persist.Names.safeRelative(workDir, path);
				} catch (IllegalArgumentException e) {
					return ToolResult.refused("dataset path refused: " + e.getMessage());
				}
				if (!Files.exists(csv)) {
					return ToolResult.refused("dataset not found: " + path);
				}
				try {
					List<String> lines = Files.readAllLines(csv);
					if (lines.isEmpty()) {
						return ToolResult.refused("dataset is empty: " + path);
					}
					List<String> columns = new ArrayList<>();
					for (String column : lines.get(0).split(",")) {
						columns.add(column.trim());
					}
					List<List<String>> samples = new ArrayList<>();
					for (String line : lines.subList(1, Math.min(lines.size(), 4))) {
						List<String> row = new ArrayList<>();
						for (String value : line.split(",")) {
							row.add(value.trim());
						}
						samples.add(row);
					}
					Map<String, Object> data = new LinkedHashMap<>();
					data.put("path", path);
					data.put("columns", columns);
					data.put("sampleRows", samples);
					return ToolResult.ok(columns.size() + " columns, " + samples.size() + " sample rows", data);
				} catch (Exception e) {
					return ToolResult.refused("cannot read dataset: " + e.getMessage());
				}
			}
		};
	}

	private static ToolRegistry.Tool runLog(FlowEngine engine) {
		return new ToolRegistry.Tool() {
			@Override
			public String name() {
				return "read_run_log";
			}

			@Override
			public String description() {
				return "The structured event log of an addressed run - node tags, levels, error messages.";
			}

			@Override
			public Map<String, String> params() {
				return Map.of("runId", "number");
			}

			@Override
			public ToolResult execute(Map<String, Object> args) {
				long runId = Long.parseLong(String.valueOf(args.getOrDefault("runId", "0")));
				try {
					List<RunEvent> events = engine.runArchive(runId);
					List<Map<String, Object>> data = new ArrayList<>();
					for (RunEvent event : events) {
						Map<String, Object> row = new LinkedHashMap<>();
						row.put("seq", event.seq());
						row.put("level", event.level());
						row.put("node", event.nodeId());
						row.put("type", event.type());
						row.put("message", event.message());
						data.add(row);
					}
					return ToolResult.ok(events.size() + " events", data);
				} catch (Exception e) {
					return ToolResult.refused("no archive for run " + runId + ": " + e.getMessage());
				}
			}
		};
	}

	private static ToolRegistry.Tool requirementThread(Supplier<core.model.RequirementThread> source) {
		return new ToolRegistry.Tool() {
			@Override
			public String name() {
				return "get_requirement_thread";
			}

			@Override
			public String description() {
				return "The user's requirement thread for the open flow - what they asked for, in order.";
			}

			@Override
			public Map<String, String> params() {
				return Map.of();
			}

			@Override
			public ToolResult execute(Map<String, Object> args) {
				core.model.RequirementThread thread = source.get();
				List<Map<String, Object>> data = new ArrayList<>();
				for (core.model.RequirementThread.Entry entry : thread.entries()) {
					Map<String, Object> row = new LinkedHashMap<>();
					row.put("author", entry.author());
					row.put("text", entry.text());
					row.put("at", entry.at());
					data.add(row);
				}
				return ToolResult.ok(data.size() + " entries", data);
			}
		};
	}

	private static ToolRegistry.Tool proposeFlow(FlowEngine engine, AgentDrafts drafts, Path workDir,
			Supplier<java.util.Set<String>> citableSources) {
		return new ToolRegistry.Tool() {
			@Override
			public String name() {
				return "propose_flow";
			}

			@Override
			public String description() {
				return "Stage a flow change (set-param, rewire, delete) as a card. Staging never touches "
						+ "the flow; the human confirm applies it. Illegal compositions are refused here.";
			}

			@Override
			public Map<String, String> params() {
				return Map.of("op", "string", "nodeId", "string", "param", "string", "value", "string",
						"from", "string", "to", "string", "rationale", "string", "citations", "object");
			}

			@Override
			public ToolResult execute(Map<String, Object> args) {
				String op = String.valueOf(args.getOrDefault("op", ""));
				FlowDocument current = engine.document();
				if (current == null) {
					return ToolResult.refused("no flow is open");
				}
				FlowDocument candidate;
				try {
					candidate = switch (op) {
						case "set-param" -> core.model.FlowEdits.setParameter(current,
								String.valueOf(args.get("nodeId")), String.valueOf(args.get("param")),
								args.get("value"));
						case "rewire" -> core.model.FlowEdits.rewire(current, String.valueOf(args.get("from")),
								String.valueOf(args.get("to")));
						case "delete" -> core.model.FlowEdits.deleteNode(current, String.valueOf(args.get("nodeId")));
						default -> throw new IllegalArgumentException("unknown op: " + op);
					};
				} catch (IllegalArgumentException e) {
					return ToolResult.refused(e.getMessage());
				}
				List<String> violations = ColumnShapes.violations(candidate, engine.registryView(), workDir);
				if (!violations.isEmpty()) {
					return ToolResult.refused("illegal composition: " + String.join("; ", violations));
				}
				@SuppressWarnings("unchecked")
				Map<String, String> citations = args.get("citations") instanceof Map<?, ?> m
						? (Map<String, String>) m
						: Map.of();
				List<String> unverifiable = Citations.verify(citations, citableSources.get());
				if (!unverifiable.isEmpty()) {
					return ToolResult.refused("unverifiable proposal: " + String.join("; ", unverifiable));
				}
				AgentDrafts.Draft draft = drafts.stage(candidate, String.valueOf(args.getOrDefault("rationale", "")),
						citations);
				return ToolResult.ok("staged card awaiting human confirm", draft.cardId());
			}
		};
	}

	private static ToolRegistry.Tool proposeDiagnosis(FlowEngine engine, AgentDrafts drafts) {
		return new ToolRegistry.Tool() {
			@Override
			public String name() {
				return "propose_diagnosis";
			}

			@Override
			public String description() {
				return "Stage a diagnosis card for a failing node: either a concrete confirmable fix "
						+ "(fixOp) or an explicit no-fix statement - never silence.";
			}

			@Override
			public Map<String, String> params() {
				return Map.of("nodeId", "string", "errorText", "string", "fixOp", "object",
						"noFixStatement", "string", "rationale", "string");
			}

			@Override
			public ToolResult execute(Map<String, Object> args) {
				String nodeId = String.valueOf(args.getOrDefault("nodeId", ""));
				String errorText = String.valueOf(args.getOrDefault("errorText", ""));
				FlowDocument fix = null;
				if (args.get("fixOp") instanceof Map<?, ?> op) {
					try {
						fix = applyFixOp(engine.document(), op);
					} catch (IllegalArgumentException e) {
						return ToolResult.refused("the proposed fix does not compose: " + e.getMessage());
					}
				}
				String noFix = args.get("noFixStatement") == null ? null
						: String.valueOf(args.get("noFixStatement"));
				if (fix == null && (noFix == null || noFix.isBlank())) {
					return ToolResult.refused("a diagnosis needs a concrete fix or an explicit no-fix statement");
				}
				if (fix != null && noFix != null && !noFix.isBlank()) {
					return ToolResult.refused("offer exactly one of fix or no-fix statement");
				}
				AgentDrafts.Draft draft = drafts.stageDiagnosis(nodeId, errorText, fix, noFix,
						String.valueOf(args.getOrDefault("rationale", "")));
				return ToolResult.ok("staged diagnosis card", draft.cardId());
			}

			private FlowDocument applyFixOp(FlowDocument doc, Map<?, ?> op) {
				String kind = String.valueOf(op.get("op"));
				Object value = op.get("value");
				if ("set-param".equals(kind) && value == null) {
					throw new IllegalArgumentException("set-param needs a value");
				}
				return switch (kind) {
					case "set-param" -> core.model.FlowEdits.setParameter(doc, String.valueOf(op.get("nodeId")),
							String.valueOf(op.get("param")), value);
					case "rewire" -> core.model.FlowEdits.rewire(doc, String.valueOf(op.get("from")),
							String.valueOf(op.get("to")));
					case "delete" -> core.model.FlowEdits.deleteNode(doc, String.valueOf(op.get("nodeId")));
					default -> throw new IllegalArgumentException("unknown fix op: " + kind);
				};
			}
		};
	}
}
