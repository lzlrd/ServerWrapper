package by.ayoo.serverwrapper;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.VarHandle;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** Replaces the current process with another program through the C library's execv, keeping the process ID. */
final class Exec {
	private static final String OS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
	private static final StructLayout CALL_STATE = Linker.Option.captureStateLayout();
	private static final VarHandle ERRNO = CALL_STATE.varHandle(MemoryLayout.PathElement.groupElement("errno"));

	private Exec() {
	}

	/** Whether this platform can replace the process. */
	static boolean supported() {
		return OS.contains("linux") || OS.contains("mac");
	}

	/**
	 * Replaces this process with {@code command}, whose first element is the program's path, after removing the
	 * environment variables in {@code unset}. Returns only if that fails, with the reason.
	 */
	static String replace(List<String> command, List<String> unset) {
		try {
			Linker linker = Linker.nativeLinker();
			SymbolLookup libc = linker.defaultLookup();
			MethodHandle unsetenv = linker.downcallHandle(find(libc, "unsetenv"), FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
			MethodHandle execv = linker.downcallHandle(find(libc, "execv"), FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS), Linker.Option.captureCallState("errno"));
			MethodHandle strerror = linker.downcallHandle(find(libc, "strerror"), FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
			Optional<MemorySegment> sigprocmask = libc.find("sigprocmask");
			try (Arena arena = Arena.ofConfined()) {
				for (String name : unset) {
					int ignored = (int) unsetenv.invokeExact(arena.allocateFrom(name));
				}
				MemorySegment argv = arena.allocate(ValueLayout.ADDRESS, command.size() + 1L);
				for (int i = 0; i < command.size(); i++)
					argv.setAtIndex(ValueLayout.ADDRESS, i, arena.allocateFrom(command.get(i)));
				argv.setAtIndex(ValueLayout.ADDRESS, command.size(), MemorySegment.NULL);
				MemorySegment path = arena.allocateFrom(command.getFirst());
				MemorySegment state = arena.allocate(CALL_STATE);
				// A new program keeps the calling thread's blocked signals, and the JVM blocks some in its threads.
				// Clear them so the new JVM starts as it would from a shell.
				if (sigprocmask.isPresent()) {
					MethodHandle setMask = linker.downcallHandle(sigprocmask.get(), FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
					// An all-zero sigset_t is the empty set; 128 bytes covers Linux's and macOS's.
					MemorySegment empty = arena.allocate(128).fill((byte) 0);
					int setMaskHow = OS.contains("mac") ? 3 : 2;
					int ignored = (int) setMask.invokeExact(setMaskHow, empty, MemorySegment.NULL);
				}
				int result = (int) execv.invokeExact(state, path, argv);
				int errno = (int) ERRNO.get(state, 0L);
				String message = ((MemorySegment) strerror.invokeExact(errno)).reinterpret(256).getString(0);
				return "execv returned " + result + ": " + message + " (errno " + errno + ")";
			}
		} catch (Throwable e) {
			return e.toString();
		}
	}

	private static MemorySegment find(SymbolLookup lookup, String name) {
		return lookup.find(name).orElseThrow(() -> new UnsupportedOperationException(name + " not found in the C library"));
	}
}
