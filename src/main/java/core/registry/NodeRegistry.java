package core.registry;

import core.engine.NodeExecutor;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * The typed descriptor registry: extension is registration, never engine surgery. The
 * standard set is the eleven shipping node types; a registered descriptor instantly appears
 * in the digest (palette/grounding source) and its executor runs in any flow.
 */
public final class NodeRegistry {

	public record Registered(NodeDescriptor descriptor, NodeExecutor executor) {
	}

	private final Map<String, Registered> nodes = new ConcurrentHashMap<>();

	public synchronized void register(NodeDescriptor descriptor, NodeExecutor executor) {
		nodes.put(descriptor.type(), new Registered(descriptor, executor));
	}

	public synchronized Registered get(String type) {
		Registered registered = nodes.get(type);
		if (registered == null) {
			throw new IllegalStateException("no node type registered: " + type);
		}
		return registered;
	}

	public synchronized List<NodeDescriptor> descriptors() {
		return nodes.values().stream().map(Registered::descriptor)
				.sorted(Comparator.comparing(NodeDescriptor::type)).toList();
	}

	/** Deterministic text digest — the grounding assembly's registry source (B4 consumes). */
	public synchronized String digest() {
		return descriptors().stream().map(d -> d.type() + " (" + d.family() + "): " + d.purpose()
				+ " | params: " + d.params().stream()
						.map(p -> p.name() + ":" + p.type() + (p.required() ? "!" : "")
								+ (p.constraint() == null || p.constraint().isBlank() ? "" : "{" + p.constraint() + "}"))
						.collect(Collectors.joining(", "))
				+ " | wires: " + d.inputs() + "in/" + d.outputs() + "out"
				+ " | seed-aware: " + (d.seedAware() ? "yes" : "no")).collect(Collectors.joining("\n"));
	}

	public static NodeRegistry standard() {
		NodeRegistry registry = new NodeRegistry();
		registry.register(core.engine.nodes.ImportCsvNode.descriptor(), core.engine.nodes.ImportCsvNode.executor());
		registry.register(core.engine.nodes.CleanDropMissingNode.descriptor(),
				core.engine.nodes.CleanDropMissingNode.executor());
		registry.register(core.engine.nodes.OlsNode.descriptor(), core.engine.nodes.OlsNode.executor());
		registry.register(core.engine.nodes.RandomForestNode.descriptor(),
				core.engine.nodes.RandomForestNode.executor());
		registry.register(core.engine.nodes.KnnNode.descriptor(), core.engine.nodes.KnnNode.executor());
		registry.register(core.engine.nodes.SvmNode.descriptor(), core.engine.nodes.SvmNode.executor());
		registry.register(core.engine.nodes.EvalRegressionNode.descriptor(),
				core.engine.nodes.EvalRegressionNode.executor());
		registry.register(core.engine.nodes.EvalClassificationNode.descriptor(),
				core.engine.nodes.EvalClassificationNode.executor());
		registry.register(core.engine.nodes.ResidualsPlotNode.descriptor(),
				core.engine.nodes.ResidualsPlotNode.executor());
		registry.register(core.engine.nodes.PredictionTableNode.descriptor(),
				core.engine.nodes.PredictionTableNode.executor());
		registry.register(core.engine.nodes.ScatterPlotNode.descriptor(),
				core.engine.nodes.ScatterPlotNode.executor());
		return registry;
	}
}
