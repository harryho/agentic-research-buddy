package core.engine.nodes;

import core.engine.NodeContext;
import core.engine.NodeExecutor;
import core.model.Node;
import core.registry.NodeDescriptor;
import core.registry.ParamSpec;
import java.util.List;
import java.util.Map;
import tech.tablesaw.api.StringColumn;
import tech.tablesaw.api.Table;

/** Regression metrics (R-squared, RMSE, MAE) against actual and predicted columns. */
public final class EvalRegressionNode {

	private EvalRegressionNode() {
	}

	public static NodeDescriptor descriptor() {
		return new NodeDescriptor("eval-regression", "evaluate", "R-squared, RMSE and MAE of a scored table",
				List.of(new ParamSpec("actual", "string", "actual numeric column", true),
						new ParamSpec("predicted", "string", "predicted numeric column", true)),
				1, 1, false);
	}

	public static NodeExecutor executor() {
		return (Node node, NodeContext ctx) -> {
			Object upstream = ctx.soleInput();
			if (!(upstream instanceof Table table)) {
				throw new IllegalStateException("eval-regression node '" + node.id() + "' needs a table upstream");
			}
			String actual = String.valueOf(node.params().get("actual"));
			String predicted = String.valueOf(node.params().get("predicted"));
			double[] a = NodeData.vector(table, actual);
			double[] p = NodeData.vector(table, predicted);
			double mean = java.util.Arrays.stream(a).average().orElse(0);
			double ssTot = 0;
			double ssRes = 0;
			double absSum = 0;
			for (int i = 0; i < a.length; i++) {
				ssTot += (a[i] - mean) * (a[i] - mean);
				ssRes += (a[i] - p[i]) * (a[i] - p[i]);
				absSum += Math.abs(a[i] - p[i]);
			}
			double r2 = ssTot == 0 ? 1.0 : 1 - ssRes / ssTot;
			double rmse = Math.sqrt(ssRes / a.length);
			double mae = absSum / a.length;
			StringColumn metricColumn = StringColumn.create("metric");
			tech.tablesaw.api.DoubleColumn valueColumn = tech.tablesaw.api.DoubleColumn.create("value");
			String[] names = {"r2", "rmse", "mae"};
			double[] values = {r2, rmse, mae};
			for (int i = 0; i < names.length; i++) {
				metricColumn.append(names[i]);
				valueColumn.append(values[i]);
			}
			return Table.create(metricColumn, valueColumn);
		};
	}
}
