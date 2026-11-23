package ui.canvas;

/**
 * A node as the canvas sees it: identity plus type, rendered as a cell. A value class so
 * the graph model's vertex comparisons stay structural.
 */
public record NodeCell(String id, String type) {

	public String label() {
		return id + "\n" + type;
	}
}
