package core.engine.nodes;

import core.engine.NodeContext;
import core.engine.NodeExecutor;
import core.model.Node;
import core.registry.NodeDescriptor;
import core.registry.ParamSpec;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import tech.tablesaw.api.Table;

/** Writes the scored table as the run's prediction-table artifact and passes it through. */
public final class PredictionTableNode {

	private PredictionTableNode() {
	}

	public static NodeDescriptor descriptor() {
		return new NodeDescriptor("prediction-table", "output", "per-row predictions written as a CSV artifact",
				List.of(new ParamSpec("out", "string", "csv file name", false)), 1, 1, false);
	}

	public static NodeExecutor executor() {
		return (Node node, NodeContext ctx) -> {
			Object upstream = ctx.soleInput();
			if (!(upstream instanceof Table table)) {
				throw new IllegalStateException("prediction-table node '" + node.id() + "' needs a table upstream");
			}
			String out = String.valueOf(node.params().getOrDefault("out", "predictions.csv"));
			Path file = ctx.workDir().resolve(out);
			table.write().csv(file.toFile());
			return table;
		};
	}
}
