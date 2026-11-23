package agent;

import core.model.RequirementThread;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * The live requirement thread for the open flow: every user question, answer, and agent
 * proposal lands as an entry in order. Persisted with the flow on version save, so the
 * thread rides the flow's history (requirement threads).
 */
public class SessionThread {

	private final String threadId;

	private final List<RequirementThread.Entry> entries = new ArrayList<>();

	public SessionThread(String threadId) {
		this.threadId = threadId;
	}

	public synchronized SessionThread add(String author, String text) {
		entries.add(new RequirementThread.Entry(author, text, Instant.now().toString()));
		return this;
	}

	/** The audit entry a re-confirmed summary rebuild adds before the version bumps. */
	public synchronized SessionThread addReconfirm(String summary) {
		return add("agent", "summary re-confirmed after edit: " + summary);
	}

	public synchronized RequirementThread snapshot() {
		return new RequirementThread(threadId, List.copyOf(entries));
	}
}
