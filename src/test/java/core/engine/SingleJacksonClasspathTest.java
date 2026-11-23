package core.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.File;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class SingleJacksonClasspathTest {

	@Test
	void exactlyOneJacksonVersionIsResolvedOnTheTestClasspath() {
		Pattern jacksonJar = Pattern.compile("jackson-[a-z-]+-(\\d[\\w.]*)\\.jar");
		Set<String> versions = new LinkedHashSet<>();
		for (String entry : System.getProperty("java.class.path").split(Pattern.quote(File.pathSeparator))) {
			Matcher m = jacksonJar.matcher(Path.of(entry).getFileName().toString());
			if (m.matches()) {
				versions.add(m.group(1));
			}
		}
		assertFalse(versions.isEmpty(), "no Jackson jars on the test classpath - the audit is blind");
		assertEquals(Set.of("2.18.2"), versions, "more than one Jackson version on the classpath");
	}
}
