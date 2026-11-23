package core.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The flow document: nodes, wires, the determinism seed, provenance (hand-built /
 * agent-built / imported), the attached requirement thread id, and the confirm history.
 * Immutable — edits produce new instances through the gate. contentHash covers the semantic
 * content (name, nodes, wires, seed) only; version and confirm history never affect it.
 */
public record FlowDocument(
		String name,
		int version,
		List<Node> nodes,
		List<Wire> wires,
		Long seed,
		Provenance provenance,
		String threadId,
		List<ConfirmHistoryEntry> confirmHistory) {

	private static final ObjectMapper MAPPER = prettyMapper();

	private static final ObjectMapper HASH_MAPPER = new ObjectMapper();

	/**
	 * Fixed LF indentation (never the platform linefeed) so manifest bytes are portable and
	 * byte-stable across machines; the printer instance must be shared by every write.
	 */
	private static ObjectMapper prettyMapper() {
		ObjectMapper mapper = new ObjectMapper();
		DefaultPrettyPrinter printer = new DefaultPrettyPrinter();
		printer.indentObjectsWith(new DefaultIndenter("  ", "\n"));
		printer.indentArraysWith(new DefaultIndenter("  ", "\n"));
		mapper.enable(SerializationFeature.INDENT_OUTPUT);
		mapper.setDefaultPrettyPrinter(printer);
		return mapper;
	}

	@JsonCreator
	public FlowDocument(
			@JsonProperty("name") String name,
			@JsonProperty("version") int version,
			@JsonProperty("nodes") List<Node> nodes,
			@JsonProperty("wires") List<Wire> wires,
			@JsonProperty("seed") Long seed,
			@JsonProperty("provenance") Provenance provenance,
			@JsonProperty("threadId") String threadId,
			@JsonProperty("confirmHistory") List<ConfirmHistoryEntry> confirmHistory) {
		this.name = name;
		this.version = version;
		this.nodes = nodes == null ? List.of() : List.copyOf(nodes);
		this.wires = wires == null ? List.of() : List.copyOf(wires);
		this.seed = seed;
		this.provenance = provenance;
		this.threadId = threadId;
		this.confirmHistory = confirmHistory == null ? List.of() : List.copyOf(confirmHistory);
	}

	public static FlowDocument fromJson(String json) {
		try {
			return MAPPER.readValue(json, FlowDocument.class);
		} catch (JsonProcessingException e) {
			throw new UncheckedJsonException(e);
		}
	}

	public String toJson() {
		try {
			return MAPPER.writeValueAsString(this);
		} catch (JsonProcessingException e) {
			throw new UncheckedJsonException(e);
		}
	}

	public FlowDocument withConfirmHistory(List<ConfirmHistoryEntry> newHistory) {
		return new FlowDocument(name, version, nodes, wires, seed, provenance, threadId, newHistory);
	}

	public String contentHash() {
		try {
			Map<String, Object> content = new LinkedHashMap<>();
			content.put("name", name);
			content.put("nodes", nodes);
			content.put("wires", wires);
			content.put("seed", seed);
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			byte[] hash = digest.digest(HASH_MAPPER.writeValueAsBytes(content));
			StringBuilder hex = new StringBuilder();
			for (byte b : hash) {
				hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
			}
			return hex.toString();
		} catch (NoSuchAlgorithmException | JsonProcessingException e) {
			throw new IllegalStateException(e);
		}
	}

	/** Unchecked wrapper so the document API stays free of checked exceptions. */
	public static class UncheckedJsonException extends RuntimeException {
		public UncheckedJsonException(Throwable cause) {
			super(cause);
		}
	}
}
