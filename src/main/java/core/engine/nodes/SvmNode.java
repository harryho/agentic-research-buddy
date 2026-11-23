package core.engine.nodes;

import core.engine.NodeContext;
import core.engine.NodeExecutor;
import core.model.Node;
import core.registry.NodeDescriptor;
import core.registry.ParamSpec;
import java.util.List;
import java.util.Map;
import smile.classification.SVM;
import tech.tablesaw.api.Table;

/** Binary support-vector-machine classification (Smile, Gaussian kernel) over numeric features. */
public final class SvmNode {

	private SvmNode() {
	}

	public static NodeDescriptor descriptor() {
		return new NodeDescriptor("svm-classify", "model", "support-vector-machine classification (binary)",
				List.of(new ParamSpec("target", "string", "binary label column", true),
						new ParamSpec("features", "string", "comma list of numeric columns", true),
						new ParamSpec("c", "number", "regularization; default 1.0", false)),
				1, 1, false);
	}

	public static NodeExecutor executor() {
		return (Node node, NodeContext ctx) -> {
			Object upstream = ctx.soleInput();
			if (!(upstream instanceof Table table)) {
				throw new IllegalStateException("svm-classify node '" + node.id() + "' needs a table upstream");
			}
			String target = String.valueOf(node.params().get("target"));
			List<String> features = NodeData.features(String.valueOf(node.params().get("features")));
			double c = Double.parseDouble(String.valueOf(node.params().getOrDefault("c", "1.0")));
			NodeData.Coded coded = NodeData.labels(table, target);
			if (coded.levels().size() != 2) {
				throw new IllegalStateException("svm-classify node '" + node.id() + "' needs a binary label");
			}
			double[][] x = NodeData.matrix(table, features);
			int[] y = new int[x.length];
			for (int i = 0; i < x.length; i++) {
				y[i] = coded.y()[i] == 0 ? -1 : 1;
			}
			smile.classification.Classifier<double[]> svm = SVM.fit(x, y, new SVM.Options(c));
			int[] predicted = new int[x.length];
			for (int i = 0; i < x.length; i++) {
				int signed = svm.predict(x[i]);
				predicted[i] = signed <= 0 ? 0 : 1;
			}
			return NodeData.withPredicted(table, predicted, coded.levels());
		};
	}
}
