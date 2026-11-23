package core.engine.nodes;

import core.engine.NodeContext;
import core.engine.NodeExecutor;
import core.model.Node;
import core.registry.NodeDescriptor;
import core.registry.ParamSpec;
import java.awt.Color;
import java.util.List;
import java.util.Map;
import tech.tablesaw.api.Table;

/** Two-column scatter rendered as a PNG (Java2D). */
public final class ScatterPlotNode {

	private ScatterPlotNode() {
	}

	public static NodeDescriptor descriptor() {
		return new NodeDescriptor("scatter-plot", "output", "two-column scatter plot as a PNG artifact",
				List.of(new ParamSpec("x", "string", "numeric x column", true),
						new ParamSpec("y", "string", "numeric y column", true),
						new ParamSpec("out", "string", "png file name", false)),
				1, 1, false);
	}

	public static NodeExecutor executor() {
		return (Node node, NodeContext ctx) -> {
			Object upstream = ctx.soleInput();
			if (!(upstream instanceof Table table)) {
				throw new IllegalStateException("scatter-plot node '" + node.id() + "' needs a table upstream");
			}
			String x = String.valueOf(node.params().get("x"));
			String y = String.valueOf(node.params().get("y"));
			String out = String.valueOf(node.params().getOrDefault("out", "scatter.png"));
			double[] xs = NodeData.vector(table, x);
			double[] ys = NodeData.vector(table, y);
			double xMin = java.util.Arrays.stream(xs).min().orElse(0);
			double xMax = java.util.Arrays.stream(xs).max().orElse(1);
			double yMin = java.util.Arrays.stream(ys).min().orElse(0);
			double yMax = java.util.Arrays.stream(ys).max().orElse(1);
			Plot.frame(ctx.workDir().resolve(out).toFile(), x + " vs " + y);
			for (int i = 0; i < xs.length; i++) {
				double px = 50 + (xs[i] - xMin) / Math.max(1e-9, xMax - xMin) * 400;
				double py = 320 - (ys[i] - yMin) / Math.max(1e-9, yMax - yMin) * 260;
				Plot.point(px, py, Color.BLUE);
			}
			Plot.write();
			return table;
		};
	}
}
