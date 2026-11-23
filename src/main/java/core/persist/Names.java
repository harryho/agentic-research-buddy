package core.persist;

import java.nio.file.Path;
import java.util.regex.Pattern;

/**
 * The one gate every caller-supplied flow, project, or layout name passes: a conservative
 * charset, no separators, no dot segments, then a containment check on the normalized
 * resolve. Blocks traversal and separator escapes before any file system call.
 */
public final class Names {

	private static final Pattern ALLOWED = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");

	private Names() {
	}

	public static void check(String name) {
		if (name == null || !ALLOWED.matcher(name).matches() || name.contains("..")) {
			throw new IllegalArgumentException("illegal flow or project name: " + name);
		}
	}

	public static Path safeChild(Path base, String name) {
		check(name);
		Path root = base.toAbsolutePath().normalize();
		Path resolved = root.resolve(name).normalize();
		if (!resolved.startsWith(root)) {
			throw new IllegalArgumentException("path escapes the library root: " + name);
		}
		return resolved;
	}

	/**
	 * The gate for caller-supplied relative paths (datasets the agent names): absolute
	 * paths and any dot-segment escape are refused before a file system call, and the
	 * normalized resolve must stay inside the workspace root.
	 */
	public static Path safeRelative(Path base, String relative) {
		if (relative == null || relative.isBlank()) {
			throw new IllegalArgumentException("empty path");
		}
		Path candidate = Path.of(relative);
		if (candidate.isAbsolute()) {
			throw new IllegalArgumentException("absolute paths are refused: " + relative);
		}
		for (Path part : candidate) {
			if (part.toString().equals("..")) {
				throw new IllegalArgumentException("path escapes the workspace root: " + relative);
			}
		}
		Path root = base.toAbsolutePath().normalize();
		Path resolved = root.resolve(candidate).normalize();
		if (!resolved.startsWith(root)) {
			throw new IllegalArgumentException("path escapes the workspace root: " + relative);
		}
		return resolved;
	}
}
