package by.ayoo.serverwrapper;

import java.io.IOException;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.management.ManagementFactory;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Starts server.jar, next to this jar, with extra Java options from server-wrapper.txt, for hosts that don't let you
 * change the startup command. The server jar stays untouched, so a launcher that checks its own jar still recognises
 * it: Leaf's QuantumLeaper only updates a jar whose hash matches a published build.
 *
 * <p>With options in the file, the wrapper replaces its own process with a new JVM that gets the host's options, then
 * the file's, then {@code -jar server.jar} and the original arguments. The process keeps its ID and no second JVM is
 * left running. Options the JDK adds itself are left for the new JVM to read again, as this one did. Options that set
 * the maximum heap are skipped, so the server keeps the heap the host gives it. If Java rejects the options, or the
 * process can't be replaced, the wrapper sets the file's system properties in its own JVM and starts the server there,
 * so a bad option never keeps the server down.
 */
public final class ServerWrapper {
	static final String SERVER_JAR = "server.jar";
	static final Path CONFIG = Path.of("server-wrapper.txt");
	/** Variables whose options the JVM lists among its input arguments, which the new JVM gets on its command line. */
	static final List<String> OPTION_VARIABLES = List.of("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS");
	private static final Pattern MAX_HEAP = Pattern.compile("-Xmx.*|-XX:(?:MaxHeapSize|MaxRAM|MaxRAMPercentage|MaxRAMFraction|MinRAMPercentage|MinRAMFraction)=.*");
	private static final Pattern HEAP_SIZE = Pattern.compile("(?:-Xm[sx]|-XX:(?:InitialHeapSize|MinHeapSize|MaxHeapSize)=)(\\d+)([kKmMgGtT]?)");
	private static final Pattern GC = Pattern.compile("-XX:[+-]Use(?:Serial|Parallel|G1|Z|Shenandoah|Epsilon)GC");
	private static final Pattern SECRET = Pattern.compile("(-D[^=]*(?i:pass|secret|token|key)[^=]*=).*");
	private static final String DEFAULT_CONFIG = """
			# Java options for server.jar, as on the java command line: -D system properties and JVM options alike.
			# Separate them with spaces or new lines, quote a value that has spaces, and start a comment with #.
			# With options here, server-wrapper.jar starts Java again in the same process: the host's options, then these.
			# Options that set the maximum heap, such as -Xmx, are skipped: the server keeps the heap the host gives it.
			""";

	private ServerWrapper() {
	}

	public static void main(String[] args) throws Throwable {
		Path serverJar = ownDirectory().resolve(SERVER_JAR);
		if (!Files.isRegularFile(serverJar))
			fail(serverJar + " not found: put the server jar next to the wrapper and name it " + SERVER_JAR);
		List<String> options = usable(readOptions(CONFIG), hostMaxHeap());
		if (!options.isEmpty()) {
			String problem;
			if (!Exec.supported()) {
				problem = "this system can't replace a running process";
			} else {
				problem = check(options, hostMaxHeap());
				if (problem == null) {
					List<String> command = command(serverJar, options, args);
					log("Starting Java again with " + options.size() + " option" + (options.size() == 1 ? "" : "s") + " from " + CONFIG + ": " + shown(command));
					System.out.flush();
					System.err.flush();
					// Only returns if the process wasn't replaced.
					problem = Exec.replace(command, OPTION_VARIABLES);
				}
			}
			log("Couldn't start Java with the options in " + CONFIG + ": " + problem);
			applyProperties(options);
		}
		runHere(serverJar, args);
	}

	/** Reads the options in the config file, writing a file with only comments if there is none. */
	static List<String> readOptions(Path config) throws IOException {
		if (Files.notExists(config))
			Files.writeString(config, DEFAULT_CONFIG);
		return tokenize(Files.readString(config, StandardCharsets.UTF_8));
	}

	/**
	 * Splits text into options the way Java reads an @argfile: whitespace separates them, single or double quotes keep
	 * spaces in one, and # at the start of an option comments out the rest of the line.
	 */
	static List<String> tokenize(String text) {
		List<String> options = new ArrayList<>();
		StringBuilder option = new StringBuilder();
		boolean inOption = false;
		char quote = 0;
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			if (quote != 0) {
				if (c == quote)
					quote = 0;
				else
					option.append(c);
			} else if (c == '"' || c == '\'') {
				quote = c;
				inOption = true;
			} else if (c == '#' && !inOption) {
				while (i + 1 < text.length() && text.charAt(i + 1) != '\n')
					i++;
			} else if (Character.isWhitespace(c)) {
				if (inOption) {
					options.add(option.toString());
					option.setLength(0);
					inOption = false;
				}
			} else {
				option.append(c);
				inOption = true;
			}
		}
		if (inOption)
			options.add(option.toString());
		return options;
	}

	/** Drops the options that would give the server more heap than the host does. */
	static List<String> usable(List<String> options, long hostMaxHeap) {
		List<String> usable = new ArrayList<>();
		for (String option : options) {
			if (MAX_HEAP.matcher(option).matches()) {
				log("Skipping " + option + ": the maximum heap stays what the host sets");
				continue;
			}
			long size = heapSize(option);
			if (size > hostMaxHeap) {
				log("Skipping " + option + ": more than the " + (hostMaxHeap >> 20) + " MB maximum heap the host sets");
				continue;
			}
			usable.add(option);
		}
		return usable;
	}

	/** The maximum heap the host set on the command line, or this JVM's if it set none. */
	static long hostMaxHeap() {
		long max = -1;
		for (String option : ManagementFactory.getRuntimeMXBean().getInputArguments())
			if (option.startsWith("-Xmx") || option.startsWith("-XX:MaxHeapSize="))
				max = heapSize(option);
		return max > 0 ? max : Runtime.getRuntime().maxMemory();
	}

	/** The size in bytes a heap size option sets, or -1 for any other option. */
	static long heapSize(String option) {
		Matcher size = HEAP_SIZE.matcher(option);
		if (!size.matches())
			return -1;
		long value = Long.parseLong(size.group(1));
		return switch (size.group(2).toLowerCase(Locale.ROOT)) {
			case "k" -> value << 10;
			case "m" -> value << 20;
			case "g" -> value << 30;
			case "t" -> value << 40;
			default -> value;
		};
	}

	/**
	 * Runs {@code java OPTIONS -version} with the host's maximum heap to see if Java accepts the options, without the
	 * ones that size or pre-touch the heap or load agents. Returns null if it does, or Java's complaint. The heap must
	 * be the real one: Java checks some options against it, such as SurvivorRatio against G1's region count.
	 */
	static String check(List<String> options, long maxHeap) throws IOException, InterruptedException {
		List<String> command = new ArrayList<>();
		command.add(javaExecutable());
		for (String option : options)
			if (heapSize(option) < 0 && !option.startsWith("-XX:SoftMaxHeapSize=") && !option.equals("-XX:+AlwaysPreTouch")
					&& !option.startsWith("-javaagent:") && !option.startsWith("-agentlib:") && !option.startsWith("-agentpath:"))
				command.add(option);
		// Only the initial heap is committed, so the full maximum costs no memory.
		command.add("-Xmx" + Math.max(maxHeap >> 20, 64) + "m");
		command.add("-version");
		ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
		OPTION_VARIABLES.forEach(builder.environment()::remove);
		Process process = builder.start();
		String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
		return process.waitFor() == 0 ? null : "Java rejected them: " + output.replace(System.lineSeparator(), " ");
	}

	/**
	 * The command for the new JVM: this one's options, the config's, then the server jar and the arguments. If the
	 * config picks a garbage collector, the host's choice is left out, since Java refuses two.
	 */
	static List<String> command(Path serverJar, List<String> options, String[] args) {
		boolean ownCollector = options.stream().anyMatch(option -> option.startsWith("-XX:+") && GC.matcher(option).matches());
		List<String> command = new ArrayList<>();
		command.add(javaExecutable());
		for (String option : withoutBuiltIn(ManagementFactory.getRuntimeMXBean().getInputArguments(), builtInOptions()))
			if (!(ownCollector && GC.matcher(option).matches()))
				command.add(option);
		command.addAll(options);
		command.add("-jar");
		command.add(serverJar.toString());
		command.addAll(List.of(args));
		return command;
	}

	/**
	 * The options the JDK passes to every JVM it starts, from the jdk/internal/vm/options resource in java.base. Oracle
	 * GraalVM uses it to turn on its compiler. Returns an empty list if the JDK has none.
	 */
	static List<String> builtInOptions() {
		try {
			Path resource = FileSystems.getFileSystem(URI.create("jrt:/")).getPath("/modules/java.base/jdk/internal/vm/options");
			return Files.isRegularFile(resource) ? tokenize(Files.readString(resource, StandardCharsets.UTF_8)) : List.of();
		} catch (IOException | RuntimeException e) {
			return List.of();
		}
	}

	/**
	 * The input arguments without the JDK's built-in options, which come first. The new JVM reads them again itself, so
	 * passing them would set them twice, and as command-line options: Java then warns that ThreadPriorityPolicy=1 may
	 * need root.
	 */
	static List<String> withoutBuiltIn(List<String> inputArguments, List<String> builtIn) {
		if (!builtIn.isEmpty() && inputArguments.size() >= builtIn.size() && inputArguments.subList(0, builtIn.size()).equals(builtIn))
			return inputArguments.subList(builtIn.size(), inputArguments.size());
		return inputArguments;
	}

	/** Sets the config's system properties in this JVM, for when Java can't be started again with them. */
	static void applyProperties(List<String> options) {
		List<String> set = new ArrayList<>();
		List<String> skipped = new ArrayList<>();
		for (String option : options) {
			if (option.startsWith("-D") && option.length() > 2) {
				int equals = option.indexOf('=');
				String name = equals < 0 ? option.substring(2) : option.substring(2, equals);
				System.setProperty(name, equals < 0 ? "" : option.substring(equals + 1));
				set.add(option);
			} else {
				skipped.add(option);
			}
		}
		log("Set " + set.size() + " system propert" + (set.size() == 1 ? "y" : "ies") + " in this JVM instead" + (set.isEmpty() ? "" : ": " + shown(set))
				+ ". Properties Java reads as it starts, such as jdk.graal.* or file.encoding, have no effect this way.");
		if (!skipped.isEmpty())
			log("Skipped " + skipped.size() + (skipped.size() == 1 ? " option that needs" : " options that need") + " the java command line: " + String.join(" ", skipped));
	}

	/** Starts the server in this JVM; the manifest's Class-Path puts server.jar on the class path. */
	static void runHere(Path serverJar, String[] args) throws Throwable {
		String mainClass;
		try (JarFile jar = new JarFile(serverJar.toFile())) {
			Manifest manifest = jar.getManifest();
			mainClass = manifest == null ? null : manifest.getMainAttributes().getValue("Main-Class");
		}
		if (mainClass == null)
			fail(SERVER_JAR + " has no Main-Class in its manifest");
		log("Starting " + SERVER_JAR + " (" + mainClass + ")");
		MethodHandle main = MethodHandles.publicLookup()
			.findStatic(Class.forName(mainClass), "main", MethodType.methodType(void.class, String[].class));
		main.invokeExact(args);
	}

	/** The command for logging, with the values of properties that look secret masked. */
	static String shown(List<String> command) {
		List<String> shown = new ArrayList<>(command.size());
		for (String part : command) {
			String masked = SECRET.matcher(part).replaceAll("$1***");
			shown.add(masked.chars().anyMatch(Character::isWhitespace) ? '"' + masked + '"' : masked);
		}
		return String.join(" ", shown);
	}

	private static String javaExecutable() {
		return Path.of(System.getProperty("java.home"), "bin", "java").toString();
	}

	private static Path ownDirectory() throws URISyntaxException {
		return Path.of(ServerWrapper.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toAbsolutePath().getParent();
	}

	private static void log(String message) {
		System.out.println("[ServerWrapper] " + message);
	}

	private static void fail(String message) {
		System.err.println("[ServerWrapper] " + message);
		System.exit(1);
	}
}
