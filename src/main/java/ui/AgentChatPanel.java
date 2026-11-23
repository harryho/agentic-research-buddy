package ui;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import javax.swing.JPanel;

/**
 * The agent chat region. The model is the machine-checked part: an ordered transcript with
 * a diagnosis handoff seam - clicking a failing node asks the chat to open the diagnosis
 * surface for that node (the diagnosis content itself is filled in by the caller).
 */
public class AgentChatPanel extends JPanel {

	public record Message(String author, String text) {
	}

	public record DiagnosisRequest(String nodeId, String errorText) {
	}

	public static class ChatModel {

		private final List<Message> messages = new ArrayList<>();

		private final List<Consumer<List<Message>>> listeners = new java.util.concurrent.CopyOnWriteArrayList<>();

		private final List<Consumer<DiagnosisRequest>> diagnosisListeners =
				new java.util.concurrent.CopyOnWriteArrayList<>();

		public void addListener(Consumer<List<Message>> listener) {
			listeners.add(listener);
			listener.accept(transcript());
		}

		public void onDiagnosisRequest(Consumer<DiagnosisRequest> listener) {
			diagnosisListeners.add(listener);
		}

		public synchronized void addMessage(String author, String text) {
			messages.add(new Message(author, text));
			notifyListeners();
		}

		public synchronized List<Message> transcript() {
			return List.copyOf(messages);
		}

		public synchronized void openDiagnosis(String nodeId, String errorText) {
			DiagnosisRequest request = new DiagnosisRequest(nodeId, errorText);
			addMessage("system", "diagnosis requested for node " + nodeId
					+ (errorText == null || errorText.isEmpty() ? "" : ": " + errorText));
			for (Consumer<DiagnosisRequest> listener : diagnosisListeners) {
				listener.accept(request);
			}
		}

		private void notifyListeners() {
			List<Message> transcript = transcript();
			for (Consumer<List<Message>> listener : listeners) {
				listener.accept(transcript);
			}
		}
	}

	private final ChatModel model = new ChatModel();

	private final javax.swing.DefaultListModel<String> rows = new javax.swing.DefaultListModel<>();

	private final javax.swing.JList<String> list = new javax.swing.JList<>(rows);

	private final javax.swing.JTextField input = new javax.swing.JTextField();

	public AgentChatPanel() {
		setLayout(new java.awt.BorderLayout());
		add(new javax.swing.JScrollPane(list), java.awt.BorderLayout.CENTER);
		add(input, java.awt.BorderLayout.SOUTH);
		input.addActionListener(e -> {
			String text = input.getText().trim();
			if (!text.isEmpty()) {
				model.addMessage("user", text);
				input.setText("");
			}
		});
		model.addListener(this::renderOnEventThread);
	}

	public ChatModel model() {
		return model;
	}

	public javax.swing.JTextField input() {
		return input;
	}

	private void renderOnEventThread(List<Message> messages) {
		if (javax.swing.SwingUtilities.isEventDispatchThread()) {
			render(messages);
		} else {
			javax.swing.SwingUtilities.invokeLater(() -> render(messages));
		}
	}

	private void render(List<Message> messages) {
		rows.clear();
		for (Message message : messages) {
			rows.addElement(message.author() + ": " + message.text());
		}
	}
}
