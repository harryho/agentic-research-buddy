package core.engine;

import core.model.ConfirmHistoryEntry;
import core.model.FlowDocument;
import core.model.FlowVersion;
import java.util.ArrayList;
import java.util.List;

/**
 * The confirm-gate state machine, structural in the engine core: every build/execute entry
 * point consults it, refusals are first-class logged events, confirm events are minted only
 * by the user-action entries, and any edit — human or agent, identically — invalidates prior
 * confirms. An edit landing while RUNNING arms supersession: the run cancels at the next
 * node boundary (the in-flight node finishes on its pre-edit version; no node starts after).
 * Every gate event is also a confirm-history row on the flow document itself, so the audit
 * trail persists with the flow and a reloaded gate derives its state from that history.
 */
public class ConfirmGate {

	private GateState state = GateState.IDLE;

	private FlowDocument document;

	private String activeCardId;

	private volatile String hashAtLastConfirm;

	private volatile boolean editedSinceLastConfirm;

	/**
	 * The supersession pair is written by the editor thread and read by the run thread at
	 * the node boundary; both are volatile and every state transition runs under the gate
	 * lock, so arming either races with the boundary check or after it — never mid-way.
	 */
	private volatile boolean supersessionArmed;

	private volatile String supersededBy;

	private final List<GateEvent> events = new ArrayList<>();

	/** Extra build-time checks beyond the structural wire rules; violations land as validation rows. */
	public interface BuildValidator {

		List<String> violations(FlowDocument document);
	}

	private volatile BuildValidator buildValidator = document -> List.of();

	/** Registers the boundary validator (column shapes live with the engine's registry and workdir). */
	public synchronized void setBuildValidator(BuildValidator validator) {
		this.buildValidator = validator;
	}

	private final List<FlowVersion> versions = new ArrayList<>();

	private final List<ConfirmHistoryEntry> history = new ArrayList<>();

	public synchronized void propose(FlowDocument draft, String cardId, String actor) {
		if (state != GateState.IDLE) {
			refuse("propose", actor);
		}
		document = draft;
		history.clear();
		history.addAll(draft.confirmHistory());
		activeCardId = cardId;
		state = GateState.PROPOSED;
		audit("proposal", "propose", actor, cardId, null);
	}

	public synchronized ConfirmEvent userConfirmBuild(String cardId, String actor) {
		if (state != GateState.PROPOSED || !cardId.equals(activeCardId)) {
			refuse("confirm-build", actor);
		}
		ConfirmEvent event = new ConfirmEvent(cardId, actor);
		state = GateState.CONFIRMED_BUILD;
		hashAtLastConfirm = document.contentHash();
		editedSinceLastConfirm = false;
		audit("confirm", "build", actor, cardId, null);
		return event;
	}

	public synchronized void discardProposal(String actor) {
		if (state != GateState.PROPOSED) {
			refuse("discard", actor);
		}
		activeCardId = null;
		state = GateState.IDLE;
		audit("discard", "discard", actor, null, null);
	}

	public synchronized FlowVersion materialize(String actor) {
		if (state != GateState.CONFIRMED_BUILD) {
			refuse("materialize", actor);
		}
		try {
			Scheduler.validate(document);
			List<String> shapeViolations = buildValidator.violations(document);
			if (!shapeViolations.isEmpty()) {
				throw new IllegalArgumentException(String.join("; ", shapeViolations));
			}
		} catch (RuntimeException e) {
			GateEvent violation = GateEvent.of("validation", "materialize", actor, state, activeCardId, e.getMessage());
			events.add(violation);
			history.add(new ConfirmHistoryEntry("validation", "materialize", actor, activeCardId, state.name(),
					violation.at(), e.getMessage()));
			document = document.withConfirmHistory(List.copyOf(history));
			throw new GateValidationException(violation);
		}
		FlowVersion version = new FlowVersion(document.version(), document, java.time.Instant.now().toString());
		versions.add(version);
		state = GateState.BUILT;
		audit("build", "materialize", actor, null, null);
		return version;
	}

	public synchronized ConfirmEvent userConfirmRun(String actor) {
		if (state != GateState.BUILT) {
			refuse("confirm-run", actor);
		}
		ConfirmEvent event = new ConfirmEvent(activeCardId, actor);
		state = GateState.CONFIRMED_RUN;
		audit("confirm", "run", actor, activeCardId, null);
		return event;
	}

	public synchronized void startRun(String actor) {
		boolean standingConfirm = (state == GateState.COMPLETED || state == GateState.FAILED_RUN
				|| state == GateState.STOPPED)
				&& !editedSinceLastConfirm
				&& document != null
				&& document.contentHash().equals(hashAtLastConfirm);
		if (state != GateState.CONFIRMED_RUN && !standingConfirm) {
			refuse("run", actor);
		}
		state = GateState.RUNNING;
		audit("run-started", "run", actor, null, null);
	}

	public synchronized void applyEdit(FlowDocument edited, String actor) {
		if (state == GateState.RUNNING) {
			supersessionArmed = true;
			supersededBy = actor;
		}
		document = edited;
		activeCardId = null;
		state = GateState.IDLE;
		editedSinceLastConfirm = true;
		audit("edit", "edit", actor, null, null);
	}

	public synchronized void runFinished(RunState terminal) {
		if (state == GateState.RUNNING) {
			state = terminal == RunState.COMPLETED ? GateState.COMPLETED
					: terminal == RunState.FAILED_RUN ? GateState.FAILED_RUN
							: GateState.STOPPED;
		}
	}

	/**
	 * Called by the run loop before each node starts. Returns the acting actor when the run
	 * was superseded by an edit (the boundary is reached, the run cancels) and logs the
	 * run-invalidated event; returns null when the run may proceed.
	 */
	public synchronized String takeSupersession() {
		if (!supersessionArmed) {
			return null;
		}
		supersessionArmed = false;
		audit("run-invalidated", "run", supersededBy, null, "superseded");
		String actor = supersededBy;
		supersededBy = null;
		return actor;
	}

	/**
	 * Adopts a freshly loaded flow document: the gate state derives from the persisted
	 * confirm history (a confirm chain without a later edit restores its confirm; an edit
	 * or invalidation at the tail leaves the gate idle), the event list rebuilds from the
	 * same rows, and the standing-confirm hash is the document's own content hash.
	 */
	public synchronized void restore(FlowDocument persisted) {
		if (state != GateState.IDLE) {
			refuse("restore", "engine");
		}
		document = persisted;
		history.clear();
		history.addAll(persisted.confirmHistory());
		events.clear();
		for (ConfirmHistoryEntry entry : history) {
			events.add(new GateEvent(entry.kind(), entry.action(), entry.actor(), entry.stateAt(), entry.cardId(),
					entry.detail(), entry.at()));
		}
		GateState derived = GateState.IDLE;
		String derivedCardId = null;
		for (ConfirmHistoryEntry entry : history) {
			switch (entry.kind()) {
				case "confirm" -> {
					if ("build".equals(entry.action())) {
						derived = GateState.CONFIRMED_BUILD;
						derivedCardId = entry.cardId();
					} else if ("run".equals(entry.action())) {
						derived = GateState.CONFIRMED_RUN;
					}
				}
				case "build" -> {
					if (derived == GateState.CONFIRMED_BUILD) {
						derived = GateState.BUILT;
					}
				}
				case "edit", "discard", "run-invalidated" -> {
					derived = GateState.IDLE;
					derivedCardId = null;
				}
				default -> {
					// proposals, refusals and validation rows carry no state weight
				}
			}
		}
		state = derived;
		activeCardId = derivedCardId;
		if (derived == GateState.CONFIRMED_BUILD || derived == GateState.BUILT || derived == GateState.CONFIRMED_RUN) {
			hashAtLastConfirm = document.contentHash();
			editedSinceLastConfirm = false;
		} else {
			hashAtLastConfirm = null;
			editedSinceLastConfirm = true;
		}
	}

	private synchronized void audit(String kind, String action, String actor, String cardId, String detail) {
		GateEvent event = GateEvent.of(kind, action, actor, state, cardId, detail);
		events.add(event);
		history.add(new ConfirmHistoryEntry(kind, action, actor, cardId, state.name(), event.at(), detail));
		if (document != null) {
			document = document.withConfirmHistory(List.copyOf(history));
		}
	}

	private synchronized void refuse(String action, String actor) {
		GateEvent event = GateEvent.of("refusal", action, actor, state, activeCardId, null);
		events.add(event);
		history.add(new ConfirmHistoryEntry("refusal", action, actor, activeCardId, state.name(), event.at(), null));
		if (document != null) {
			document = document.withConfirmHistory(List.copyOf(history));
		}
		throw new GateRefusalException(action, state, event);
	}

	public synchronized GateState state() {
		return state;
	}

	public synchronized FlowDocument document() {
		return document;
	}

	public synchronized List<GateEvent> events() {
		return List.copyOf(events);
	}

	public synchronized List<FlowVersion> versions() {
		return List.copyOf(versions);
	}
}
