package core.engine;

/** Thrown for every attempt to build or execute outside a gate-permitted state. */
public class GateRefusalException extends RuntimeException {

	private final String action;

	private final GateState stateAt;

	GateRefusalException(String action, GateState stateAt, GateEvent event) {
		super("gate refused " + action + " in state " + stateAt + " (" + event.at() + ")");
		this.action = action;
		this.stateAt = stateAt;
	}

	public String action() {
		return action;
	}

	public GateState stateAt() {
		return stateAt;
	}
}
