package core.engine;

/** Terminal states of a run. The gate states that precede RUNNING arrive with the confirm gate. */
public enum RunState {
	RUNNING,
	COMPLETED,
	FAILED_RUN,
	STOPPED
}
