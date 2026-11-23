package core.engine.nodes;

import core.engine.NodeContext;
import core.engine.NodeExecutor;
import core.model.Node;
import core.registry.NodeDescriptor;
import core.registry.ParamSpec;
import java.util.List;
import java.util.Map;
import tech.tablesaw.api.Table;

/** k-nearest-neighbour classification over numeric features; ties break to the lowest label. */
public final class KnnNode {

	private KnnNode() {
	}

	public static NodeDescriptor descriptor() {
		return new NodeDescriptor("knn-classify", "model", "k-nearest-neighbour classification",
				List.of(new ParamSpec("target", "string", "label column", true),
						new ParamSpec("features", "string", "comma list of numeric columns", true),
						new ParamSpec("k", "number", "neighbours; default 5", false)),
				1, 1, false);
	}

	public static NodeExecutor executor() {
		return (Node node, NodeContext ctx) -> {
			Object upstream = ctx.soleInput();
			if (!(upstream instanceof Table table)) {
				throw new IllegalStateException("knn-classify node '" + node.id() + "' needs a table upstream");
			}
			String target = String.valueOf(node.params().get("target"));
			List<String> features = NodeData.features(String.valueOf(node.params().get("features")));
			int k = Math.max(1, Integer.parseInt(String.valueOf(node.params().getOrDefault("k", "5"))));
			NodeData.Coded coded = NodeData.labels(table, target);
			double[][] x = NodeData.matrix(table, features);
			int[] predicted = new int[x.length];
			for (int r = 0; r < x.length; r++) {
				double[] bestDist = new double[Math.min(k, x.length)];
				int[] bestLabel = new int[bestDist.length];
				java.util.Arrays.fill(bestDist, Double.MAX_VALUE);
				for (int other = 0; other < x.length; other++) {
					if (other == r) {
						continue;
					}
					double d = 0;
					for (int f = 0; f < x[r].length; f++) {
						double diff = x[r][f] - x[other][f];
						d += diff * diff;
					}
					int slot = -1;
					for (int s = 0; s < bestDist.length; s++) {
						if (d < bestDist[s] && (slot == -1 || bestDist[slot] > d)) {
							slot = s;
						}
					}
					if (slot >= 0) {
						bestDist[slot] = d;
						bestLabel[slot] = coded.y()[other];
					}
				}
				int[] votes = new int[coded.levels().size()];
				for (int s = 0; s < bestLabel.length; s++) {
					if (bestDist[s] < Double.MAX_VALUE) {
						votes[bestLabel[s]]++;
					}
				}
				int winner = 0;
				for (int label = 1; label < votes.length; label++) {
					if (votes[label] > votes[winner]) {
						winner = label;
					}
				}
				predicted[r] = winner;
			}
			return NodeData.withPredicted(table, predicted, coded.levels());
		};
	}
}
