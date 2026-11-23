package core.engine;

/**
 * One run-log event. seq is assigned by the RunLog under the append lock — strictly ordered,
 * gapless. nodeId is the empty string for run-level events.
 */
public record RunEvent(String ts, long seq, String level, String nodeId, String type, String message) {
}
