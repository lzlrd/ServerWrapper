# ServerWrapper

Starts `server.jar` with Java options of your choosing, for hosts that don't let you change the startup command. `server.jar` stays untouched, so a launcher that checks its own jar keeps working: Leaf's self-updater only updates a jar whose hash matches a published build.

## Use

1. Put `server-wrapper.jar` from [Releases](https://github.com/lzlrd/ServerWrapper/releases) next to `server.jar`.
2. Make the host start `server-wrapper.jar` instead. On PebbleHost, type its name in the text field under the JAR selector.
3. Start the server once to create `server-wrapper.txt`, add your options to it, and restart:

   ```
   # Leaf
   -DLeaf.enableFMA=true -DLeaf.enable-io-uring=true
   -XX:+UseCompactObjectHeaders
   ```

Write options as on the java command line, separated by spaces or new lines. Quote a value that has spaces, and start a comment with `#`.

Needs Java 25 or later.

## How it works

With options in the file, the wrapper replaces its own process with a new JVM: `java <the host's options> <yours> -jar server.jar <the host's arguments>`. The process keeps its ID, no second JVM is left running, and console input and stop signals reach the server directly. Each start logs the full command, so you can see what the host passes.

- Options that set the maximum heap, such as `-Xmx` or `-XX:MaxRAMPercentage`, are skipped, and so is an `-Xms` above the host's maximum. The server keeps the heap the host gives it.
- A garbage collector you choose, such as `-XX:+UseZGC`, replaces the host's choice.
- Options the JDK adds to every start, such as Oracle GraalVM's built-in ones, aren't passed again. The new JVM reads them itself, as the first one did.
- Before handing over, the wrapper runs `java <your options> -version`. If Java rejects an option, the log says why and the server starts in the wrapper's own JVM with only your `-D` properties. It does the same on Windows, which can't replace a running process. Properties Java reads as it starts, such as `jdk.graal.*` or `file.encoding`, have no effect that way.
- GraalVM reads `jdk.graal.*` options when its compiler starts, which that check may not reach. If the server stops with a Graal options error, fix that line.

With no options, the wrapper runs `server.jar` in its own JVM.

If Leaf reports a loader update, replace `server.jar` with the file it names, not `server-wrapper.jar`.

## Build

```sh
./gradlew build
```

Needs JDK 25. Writes `build/libs/ServerWrapper-1.1.jar`.

## Licence

[PolyForm Noncommercial 1.0.0](LICENSE.md).
