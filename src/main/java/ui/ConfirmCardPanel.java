package ui;

import agent.AgentDrafts;
import agent.ProposalCards;
import java.awt.BorderLayout;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;

/**
 * The staged-proposal cards: what the agent proposes, why, and its citations - Apply is the
 * human confirm that lands the edit (agent-attributed, through the gate); Decline drops the
 * card. Headless logic lives in ProposalCards and AgentDrafts; this panel renders.
 */
public class ConfirmCardPanel extends JPanel {

	private final DefaultListModel<String> rows = new DefaultListModel<>();

	private final JList<String> list = new JList<>(rows);

	private final JButton apply = new JButton("apply");

	private final JButton decline = new JButton("decline");

	private final javax.swing.JLabel status = new javax.swing.JLabel(" ");

	private AgentDrafts drafts;

	private core.model.FlowDocument current;

	private Runnable onChange = () -> {
	};

	/** The shell hooks its gate-label refresh here - a card decision moves the gate. */
	public void onChange(Runnable listener) {
		this.onChange = listener;
	}

	private final java.util.Map<String, String> cardText = new java.util.LinkedHashMap<>();

	public ConfirmCardPanel() {
		setLayout(new BorderLayout());
		setBorder(javax.swing.BorderFactory.createTitledBorder("agent proposals"));
		add(new JScrollPane(list), BorderLayout.CENTER);
		JPanel buttons = new JPanel(new java.awt.FlowLayout());
		buttons.add(apply);
		buttons.add(decline);
		buttons.add(status);
		add(buttons, BorderLayout.SOUTH);
		apply.setEnabled(false);
		decline.setEnabled(false);
		apply.addActionListener(e -> applySelected());
		decline.addActionListener(e -> declineSelected());
	}

	public void bind(AgentDrafts drafts, core.model.FlowDocument current) {
		this.drafts = drafts;
		this.current = current;
		refresh();
	}

	/** Re-projects the staged cards; called after the loop stages or the user decides. */
	public final void refresh() {
		cardText.clear();
		rows.clear();
		if (drafts == null) {
			return;
		}
		for (String cardId : drafts.stagedCards()) {
			AgentDrafts.Draft draft = drafts.get(cardId);
			String text = ProposalCards.summarize(current, draft);
			cardText.put(cardId, text);
			rows.addElement(cardId + ": " + text.split("\n")[0]);
		}
		boolean any = !rows.isEmpty();
		apply.setEnabled(any);
		decline.setEnabled(any);
	}

	private void applySelected() {
		String cardId = selectedCard();
		if (cardId != null && drafts != null) {
			current = drafts.apply(cardId);
			status.setText("applied " + cardId);
			refresh();
			onChange.run();
		}
	}

	private void declineSelected() {
		String cardId = selectedCard();
		if (cardId != null && drafts != null) {
			drafts.discard(cardId);
			status.setText("declined " + cardId);
			refresh();
			onChange.run();
		}
	}

	private String selectedCard() {
		int index = list.getSelectedIndex();
		return index < 0 || index >= rows.size() ? null : String.valueOf(rows.get(index)).split(":")[0];
	}
}
