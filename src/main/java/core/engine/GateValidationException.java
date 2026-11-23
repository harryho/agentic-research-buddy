package core.engine;

/**
 * Thrown when materialize is attempted on a document that violates a structural rule
 * (e.g. an input port fed by more than one wire). The gate logs a validation event and
 * stays in its state - a failed build is a refusal, not a state.
 */
public class GateValidationException extends RuntimeException {

	GateValidationException(GateEvent event) {
		super("build rejected: " + event.detail() + " (state " + event.stateAt() + ")");
	}

	GateValidationException(String message) {
		super(message);
	}
}
