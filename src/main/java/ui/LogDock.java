package ui;

import core.engine.RunEvent;
import java.util.ArrayDeque;
import java.util.List;
import java.util.function.Consumer;
import javax.swing.JPanel;

/**
 * The log dock: a bounded window over the newest run events with a substring filter. The
 * model (replay from an archive, live push, trimming, filter) is headless-testable; the
 * Swing panel only renders model changes onto the event thread.
 */
public class LogDock extends JPanel {

	public static class LogDockModel {

		private final ArrayDeque<RunEvent> window = new ArrayDeque<>();

		private final List<Consumer<List<RunEvent>>> listeners = new java.util.concurrent.CopyOnWriteArrayList<>();

		private final List<java.util.function.Consumer<RunEvent>> errorHandlers =
				new java.util.concurrent.CopyOnWriteArrayList<>();

		private String filter = "";

		/** Error-row clicks hand the referenced event to these handlers. */
		public void onError(java.util.function.Consumer<RunEvent> handler) {
			errorHandlers.add(handler);
		}

		/** The click seam: an error row carries its node reference to the handlers. */
		public void clickEvent(RunEvent event) {
			if (event != null && "error".equals(event.level()) && !event.nodeId().isEmpty()) {
				for (java.util.function.Consumer<RunEvent> handler : errorHandlers) {
					handler.accept(event);
				}
			}
		}

		public void addListener(Consumer<List<RunEvent>> listener) {
			listeners.add(listener);
			listener.accept(visible());
		}

		public synchronized void replay(List<RunEvent> events) {
			window.clear();
			for (RunEvent event : events) {
				push(event);
			}
			notifyListeners();
		}

		public synchronized void onEvent(RunEvent event) {
			push(event);
			notifyListeners();
		}

		public synchronized void setFilter(String text) {
			this.filter = text == null ? "" : text;
			notifyListeners();
		}

		public synchronized List<RunEvent> visible() {
			String f = filter.toLowerCase();
			return window.stream()
					.filter(e -> f.isEmpty()
							|| e.type().toLowerCase().contains(f)
							|| e.nodeId().toLowerCase().contains(f)
							|| e.level().toLowerCase().contains(f)
							|| e.message().toLowerCase().contains(f))
					.toList();
		}

		private void push(RunEvent event) {
			window.addLast(event);
			while (window.size() > core.engine.RunLog.DOCK_BUFFER_CAPACITY) {
				window.pollFirst();
			}
		}

		private void notifyListeners() {
			List<RunEvent> view = visible();
			for (Consumer<List<RunEvent>> listener : listeners) {
				listener.accept(view);
			}
		}
	}

	private final LogDockModel model = new LogDockModel();

	private List<RunEvent> renderedRows = List.of();

	private final javax.swing.DefaultListModel<String> rows = new javax.swing.DefaultListModel<>();

	private final javax.swing.JList<String> list = new javax.swing.JList<>(rows);

	private final javax.swing.JTextField filterField = new javax.swing.JTextField();

	public LogDock() {
		setLayout(new java.awt.BorderLayout());
		add(filterField, java.awt.BorderLayout.NORTH);
		add(new javax.swing.JScrollPane(list), java.awt.BorderLayout.CENTER);
		filterField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
			@Override
			public void insertUpdate(javax.swing.event.DocumentEvent e) {
				applyFilter();
			}

			@Override
			public void removeUpdate(javax.swing.event.DocumentEvent e) {
				applyFilter();
			}

			@Override
			public void changedUpdate(javax.swing.event.DocumentEvent e) {
				applyFilter();
			}
		});
		model.addListener(this::renderOnEventThread);
		list.addMouseListener(new java.awt.event.MouseAdapter() {
			@Override
			public void mouseClicked(java.awt.event.MouseEvent e) {
				int index = list.locationToIndex(e.getPoint());
				if (index >= 0 && index < renderedRows.size()) {
					model.clickEvent(renderedRows.get(index));
				}
			}
		});
	}

	public void onError(java.util.function.Consumer<RunEvent> handler) {
		model.onError(handler);
	}

	private void applyFilter() {
		model.setFilter(filterField.getText());
	}

	public LogDockModel model() {
		return model;
	}

	private void renderOnEventThread(List<RunEvent> events) {
		if (javax.swing.SwingUtilities.isEventDispatchThread()) {
			render(events);
		} else {
			javax.swing.SwingUtilities.invokeLater(() -> render(events));
		}
	}

	private void render(List<RunEvent> events) {
		renderedRows = events;
		rows.clear();
		for (RunEvent event : events) {
			rows.addElement(event.ts() + " [" + event.level() + "] " + event.nodeId() + " " + event.type()
					+ (event.message().isEmpty() ? "" : " - " + event.message()));
		}
	}
}
