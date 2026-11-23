package ui;

import core.persist.FlowStore;
import core.persist.Library;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTree;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;

/**
 * The flow tree region: the library's read-only demos, user projects, and their immutable
 * versions. The model is a plain projection of the library tree; the Swing tree renders it.
 */
public class LibraryPanel extends JPanel {

	public static class LibraryTreeModel {

		private final Library library;

		public LibraryTreeModel(Library library) {
			this.library = library;
		}

		public List<String> rows() {
			List<String> rows = new ArrayList<>();
			for (String demo : library.demos()) {
				rows.add("demo:" + demo);
			}
			for (String project : library.projects()) {
				rows.add("project:" + project);
				for (int version : library.versions(project)) {
					rows.add("version:" + project + ":" + version);
				}
			}
			return rows;
		}

		public LibraryTreeModel reload() {
			return this;
		}
	}

	private final Library library;

	private final DefaultMutableTreeNode root = new DefaultMutableTreeNode("library");

	private final JTree tree = new JTree(new DefaultTreeModel(root));

	public LibraryPanel(Library library) {
		this(library, name -> {
		});
	}

	/** The open gesture: double-click a demo (or version) and hand the name to the caller. */
	public LibraryPanel(Library library, java.util.function.Consumer<String> onOpen) {
		this.library = library;
		setLayout(new java.awt.BorderLayout());
		add(new JScrollPane(tree), java.awt.BorderLayout.CENTER);
		tree.addMouseListener(new java.awt.event.MouseAdapter() {
			@Override
			public void mouseClicked(java.awt.event.MouseEvent e) {
				if (e.getClickCount() == 2) {
					javax.swing.tree.TreePath path = tree.getPathForLocation(e.getX(), e.getY());
					if (path != null) {
						onOpen.accept(String.valueOf(path.getLastPathComponent()));
					}
				}
			}
		});
		refresh();
	}

	/** Re-projects the library into the tree; called after installs, copies, and saves. */
	public final void refresh() {
		root.removeAllChildren();
		DefaultMutableTreeNode demos = new DefaultMutableTreeNode("demos");
		for (String demo : library.demos()) {
			demos.add(new DefaultMutableTreeNode("demo:" + demo));
		}
		root.add(demos);
		DefaultMutableTreeNode projects = new DefaultMutableTreeNode("projects");
		for (String project : library.projects()) {
			DefaultMutableTreeNode node = new DefaultMutableTreeNode("project:" + project);
			for (int version : library.versions(project)) {
				node.add(new DefaultMutableTreeNode("version:" + project + ":" + version));
			}
			projects.add(node);
		}
		root.add(projects);
		((DefaultTreeModel) tree.getModel()).reload();
	}

	public FlowStore.LoadedFlow openDemo(String name) {
		return library.openDemo(name);
	}
}
