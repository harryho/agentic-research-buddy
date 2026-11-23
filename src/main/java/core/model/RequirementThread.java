package core.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.ArrayList;
import java.util.List;

/**
 * The requirement conversation a flow grew out of. Persisted as plain markdown (thread.md):
 * a "# <threadId>" header, then per entry a "- <ts> **<author>**" line followed by the entry
 * text on the next line(s). Entry text must not contain lines starting with "- ".
 */
public record RequirementThread(String threadId, List<Entry> entries) {

	public record Entry(String author, String text, String at) {

		@JsonCreator
		public Entry(
				@JsonProperty("author") String author,
				@JsonProperty("text") String text,
				@JsonProperty("at") String at) {
			this.author = author;
			this.text = text;
			this.at = at;
		}
	}

	@JsonCreator
	public RequirementThread(
			@JsonProperty("threadId") String threadId,
			@JsonProperty("entries") List<Entry> entries) {
		this.threadId = threadId;
		this.entries = entries == null ? List.of() : List.copyOf(entries);
	}

	public String toMarkdown() {
		StringBuilder md = new StringBuilder();
		md.append("# ").append(threadId).append("\n\n");
		for (Entry entry : entries) {
			md.append("- ").append(entry.at()).append(" **").append(entry.author()).append("**\n");
			md.append(entry.text()).append("\n\n");
		}
		return md.toString();
	}

	public static RequirementThread fromMarkdown(String markdown) {
		List<String> lines = new ArrayList<>(List.of(markdown.split("\n", -1)));
		String header = lines.remove(0);
		String id = header.startsWith("# ") ? header.substring(2) : header;
		List<Entry> parsed = new ArrayList<>();
		int i = 0;
		while (i < lines.size()) {
			String line = lines.get(i);
			if (line.isBlank()) {
				i++;
				continue;
			}
			if (!line.startsWith("- ")) {
				throw new IllegalArgumentException("thread.md entry must start with '- ' at line " + (i + 2));
			}
			String meta = line.substring(2);
			int sep = meta.indexOf(" **");
			if (sep < 0 || !meta.endsWith("**")) {
				throw new IllegalArgumentException("thread.md entry header malformed: " + line);
			}
			String at = meta.substring(0, sep);
			String author = meta.substring(sep + 3, meta.length() - 2);
			i++;
			StringBuilder text = new StringBuilder();
			while (i < lines.size() && !lines.get(i).isBlank() && !lines.get(i).startsWith("- ")) {
				if (text.length() > 0) {
					text.append("\n");
				}
				text.append(lines.get(i));
				i++;
			}
			parsed.add(new Entry(author, text.toString(), at));
		}
		return new RequirementThread(id, parsed);
	}
}
