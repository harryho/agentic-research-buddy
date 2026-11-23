package core.engine.nodes;

import core.engine.NodeContext;
import core.engine.NodeExecutor;
import core.model.Node;
import core.registry.NodeDescriptor;
import core.registry.ParamSpec;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import tech.tablesaw.api.DoubleColumn;
import tech.tablesaw.api.Table;

/**
 * Bagged regression-tree ensemble over numeric features: depth-1 stumps fit on seeded
 * bootstrap samples. All randomness derives from the run seed via the node context.
 */
public final class RandomForestNode {

	private RandomForestNode() {
	}

	record Stump(int feature, double threshold, double left, double right) {

		double predict(double[] row) {
			return row[feature] <= threshold ? left : right;
		}
	}

	public static NodeDescriptor descriptor() {
		return new NodeDescriptor("random-forest", "model", "bagged regression-tree ensemble (seeded bootstrap)",
				List.of(new ParamSpec("target", "string", "numeric target column", true),
						new ParamSpec("features", "string", "comma list of numeric columns", true),
						new ParamSpec("trees", "number", "ensemble size; default 20", false)),
				1, 1, true);
	}

	public static NodeExecutor executor() {
		return (Node node, NodeContext ctx) -> {
			Object upstream = ctx.soleInput();
			if (!(upstream instanceof Table table)) {
				throw new IllegalStateException("random-forest node '" + node.id() + "' needs a table upstream");
			}
			String target = String.valueOf(node.params().get("target"));
			List<String> features = NodeData.features(String.valueOf(node.params().get("features")));
			int trees = Integer.parseInt(String.valueOf(node.params().getOrDefault("trees", "20")));
			double[] y = NodeData.vector(table, target);
			double[][] x = NodeData.matrix(table, features);
			Random rng = ctx.newRandom();
			List<Stump> ensemble = new ArrayList<>();
			for (int t = 0; t < trees; t++) {
				int n = x.length;
				int[] bag = new int[n];
				for (int i = 0; i < n; i++) {
					bag[i] = rng.nextInt(n);
				}
				int feature = rng.nextInt(features.size());
				double[] bagFeature = new double[n];
				for (int i = 0; i < n; i++) {
					bagFeature[i] = x[bag[i]][feature];
				}
				java.util.Arrays.sort(bagFeature);
				double threshold = bagFeature[n / 2];
				double leftSum = 0;
				int leftCount = 0;
				double rightSum = 0;
				int rightCount = 0;
				for (int i = 0; i < n; i++) {
					if (x[bag[i]][feature] <= threshold) {
						leftSum += y[bag[i]];
						leftCount++;
					} else {
						rightSum += y[bag[i]];
						rightCount++;
					}
				}
				double left = leftCount == 0 ? 0 : leftSum / leftCount;
				double right = rightCount == 0 ? left : rightSum / rightCount;
				ensemble.add(new Stump(feature, threshold, left, right));
			}
			Table out = table.copy();
			DoubleColumn predicted = DoubleColumn.create("predicted");
			for (int r = 0; r < x.length; r++) {
				double sum = 0;
				for (Stump stump : ensemble) {
					sum += stump.predict(x[r]);
				}
				predicted.append(sum / ensemble.size());
			}
			out.addColumns(predicted);
			return out;
		};
	}
}
