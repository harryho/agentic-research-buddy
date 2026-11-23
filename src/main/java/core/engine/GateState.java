package core.engine;

/** The confirm-gate states. Every build/execute entry point consults it; there is no second path. */
public enum GateState {
	IDLE,
	PROPOSED,
	CONFIRMED_BUILD,
	BUILT,
	CONFIRMED_RUN,
	RUNNING,
	COMPLETED,
	FAILED_RUN,
	STOPPED
}
