package core.engine.nodes;

import core.engine.NodeContext;
import core.engine.NodeExecutor;
import core.model.Node;
import core.registry.NodeDescriptor;
import core.registry.ParamSpec;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import tech.tablesaw.api.Table;

/** Residual-vs-fitted scatter rendered as a PNG (Java2D). */
public final class ResidualsPlotNode {

	private ResidualsPlotNode() {
	}

	public static NodeDescriptor descriptor() {
		return new NodeDescriptor("residuals-plot", "output", "residual-vs-fitted plot as a PNG artifact",
				List.of(new ParamSpec("actual", "string", "actual numeric column", true),
						new ParamSpec("predicted", "string", "predicted numeric column", true),
						new ParamSpec("out", "string", "png file name", false)),
				1, 1, false);
	}

	public static NodeExecutor executor() {
		return (Node node, NodeContext ctx) -> {
			Object upstream = ctx.soleInput();
			if (!(upstream instanceof Table table)) {
				throw new IllegalStateException("residuals-plot node '" + node.id() + "' needs a table upstream");
			}
			String actual = String.valueOf(node.params().get("actual"));
			String predicted = String.valueOf(node.params().get("predicted"));
			String out = String.valueOf(node.params().getOrDefault("out", "residuals.png"));
			double[] a = NodeData.vector(table, actual);
			double[] p = NodeData.vector(table, predicted);
			Path file = ctx.workDir().resolve(out);
			Plot.frame(file.toFile(), "residual vs fitted");
			for (int i = 0; i < a.length; i++) {
				Plot.point(i * 400.0 / Math.max(1, a.length - 1) + 50,
						300 - (a[i] - p[i]) * 20, Color.ORANGE);
			}
			Plot.write();
			return table;
		};
	}
}

/** Shared Java2D plot plumbing for the plot nodes. */
final class Plot {

	private static BufferedImage image;
	private static Graphics2D g;
	private static File target;

	private Plot() {
	}

	static void frame(File file, String label) {
		target = file;
		image = new BufferedImage(500, 350, BufferedImage.TYPE_INT_RGB);
		g = image.createGraphics();
		g.setColor(Color.WHITE);
		g.fillRect(0, 0, 500, 350);
		g.setColor(Color.DARK_GRAY);
		g.setStroke(new BasicStroke(2));
		g.drawString(label, 20, 24);
		g.drawRect(40, 40, 420, 280);
	}

	static void point(double x, double y, Color color) {
		g.setColor(color);
		g.fillOval((int) x, (int) y, 5, 5);
	}

	static void line(double x1, double y1, double x2, double y2, Color color) {
		g.setColor(color);
		g.drawLine((int) x1, (int) y1, (int) x2, (int) y2);
	}

	static void write() {
		try {
			ImageIO.write(image, "png", target);
		} catch (Exception e) {
			throw new IllegalStateException("failed to write plot artifact: " + target, e);
		} finally {
			g.dispose();
		}
	}
}
