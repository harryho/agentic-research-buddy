package core.engine;

import core.model.FlowDocument;
import core.model.Node;
import core.model.Wire;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Static column-shape inference over the wire graph: what columns each node's output table
 * carries, derived without executing anything. Eval nodes must see their actual/predicted
 * columns in the wired upstream shape - bad wiring fails at the gate, not mid-run. The
 * header of an import dataset is read at validate time; when a dataset is missing the shape
 * is unknown, checks degrade silently, and the dataset surfaces as a run-time node error -
 * exactly as it did before this check existed. Model nodes add the predicted column; OLS
 * outputs its coefficient summary.
 */
public final class ColumnShapes {

	private ColumnShapes() {
	}

	/**
	 * The declared output contract for a node type - static, no execution. The digest's
	 * composition data: what a node adds (kind passthrough keeps upstream columns; replace
	 * swaps them), and which columns it requires of its upstream.
	 */
	public record OutputContract(String kind, List<String> adds, List<String> requires) {
	}

	public static OutputContract contract(String type) {
		return switch (type) {
			case "import-csv" -> new OutputContract("dataset", List.of(), List.of());
			case "ols-regression" -> new OutputContract("replace", List.of("term", "coefficient"), List.of());
			case "random-forest", "knn-classify", "svm-classify" -> new OutputContract("passthrough",
					List.of("predicted"), List.of());
			case "eval-regression", "eval-classification" -> new OutputContract("replace",
					List.of("metric", "value"), List.of("predicted"));
			default -> new OutputContract("passthrough", List.of(), List.of());
		};
	}

	public static List<String> violations(FlowDocument doc, core.registry.NodeRegistry registry, Path workDir) {
		List<String> violations = new ArrayList<>();
		Map<String, String> feeder = new HashMap<>();
		for (Wire wire : doc.wires()) {
			feeder.put(wire.to(), wire.from());
		}
		Map<String, Set<String>> shapes = new HashMap<>();

		Map<String, Set<String>> unknown = new HashMap<>();
		for (Node node : Scheduler.executionOrder(doc)) {
			Set<String> upstream = feeder.containsKey(node.id())
					? shapes.get(feeder.get(node.id()))
					: Set.of();
			boolean known = upstream != null;
			Set<String> out = known ? new TreeSet<>(upstream) : null;
			switch (node.type()) {
				case "import-csv" -> {
					String path = String.valueOf(node.params().getOrDefault("path", ""));
					Path csv = workDir.resolve(path);
					if (!Files.exists(csv)) {
						out = null;
					} else {
						out = new TreeSet<>();
						out.addAll(header(csv));
					}
				}
				case "ols-regression" -> out = known ? new TreeSet<>(List.of("term", "coefficient")) : null;
				case "random-forest", "knn-classify", "svm-classify" -> {
					if (known) {
						out.add("predicted");
					}
				}
				case "eval-regression", "eval-classification" -> {
					if (known) {
						String actual = String.valueOf(node.params().getOrDefault("actual", ""));
						List<String> missing = new ArrayList<>();
						if (!upstream.contains(actual)) {
							missing.add(actual);
						}
						if (!upstream.contains("predicted")) {
							missing.add("predicted");
						}
						if (!missing.isEmpty()) {
							violations.add(node.type() + " node '" + node.id()
									+ "': upstream table lacks column(s): " + String.join(", ", missing));
						}
					}
					out = known ? new TreeSet<>(List.of("metric", "value")) : null;
				}
				default -> {
					// pass-through: clean, plots, prediction table, and registered extensions
				}
			}
			shapes.put(node.id(), out);
		}
		return violations;
	}

	private static List<String> header(Path csv) {
		try {
			String first = Files.readAllLines(csv).get(0);
			List<String> columns = new ArrayList<>();
			for (String column : first.split(",")) {
				columns.add(column.trim());
			}
			return columns;
		} catch (Exception e) {
			throw new IllegalStateException("cannot read dataset header: " + csv, e);
		}
	}
}
