package core.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The run log: an append-only JSONL archive (the record of truth) plus a bounded in-memory
 * dock buffer (the live view). The dock is trimmed to its capacity from the head; the
 * archive never trims. Replay re-reads the archive file.
 */
public class RunLog {

	public static final int DOCK_BUFFER_CAPACITY = 100;

	private final Path file;

	private static final ObjectMapper mapper = new ObjectMapper();

	private final ArrayDeque<RunEvent> dock = new ArrayDeque<>();

	private final java.util.List<java.util.function.Consumer<RunEvent>> listeners =
			new java.util.concurrent.CopyOnWriteArrayList<>();

	/** Subscribes to live events as they append; the UI binds by push, never by polling. */
	public void addListener(java.util.function.Consumer<RunEvent> listener) {
		listeners.add(listener);
	}

	private final BufferedWriter writer;

	private long seq;

	public RunLog(Path file) {
		this.file = file;
		try {
			Files.createDirectories(file.toAbsolutePath().getParent());
			this.writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	public synchronized RunEvent append(String level, String nodeId, String type, String message) {
		RunEvent event = new RunEvent(Instant.now().toString(), ++seq, level, nodeId == null ? "" : nodeId, type,
				message == null ? "" : message);
		try {
			Map<String, Object> line = new LinkedHashMap<>();
			line.put("ts", event.ts());
			line.put("seq", event.seq());
			line.put("level", event.level());
			line.put("node", event.nodeId());
			line.put("type", event.type());
			line.put("message", event.message());
			writer.write(mapper.writeValueAsString(line));
			writer.newLine();
			writer.flush();
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		dock.addLast(event);
		while (dock.size() > DOCK_BUFFER_CAPACITY) {
			dock.pollFirst();
		}
		for (java.util.function.Consumer<RunEvent> listener : listeners) {
			listener.accept(event);
		}
		return event;
	}

	public synchronized List<RunEvent> dockSnapshot() {
		return List.copyOf(dock);
	}

	/** Re-reads the archive file — the dock's replay source, and the record of truth. */
	public List<RunEvent> replay() {
		return replay(file);
	}

	/** Re-reads an addressed run's archive file from disk. */
	public static List<RunEvent> replay(Path archiveFile) {
		Path file = archiveFile;
		try {
			List<RunEvent> events = new ArrayList<>();
			for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
				if (line.isBlank()) {
					continue;
				}
				JsonNode n = mapper.readTree(line);
				events.add(new RunEvent(
						n.get("ts").asText(),
						n.get("seq").asLong(),
						n.get("level").asText(),
						n.get("node").asText(),
						n.get("type").asText(),
						n.get("message").asText()));
			}
			return events;
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	void close() {
		try {
			writer.close();
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}
}
