# samples/

Independently buildable examples. Each subfolder is a complete Maven
project: `cd` into it, `mvn package`, and you have a runnable fat
jar.

Two families:

- **JNI samples** use the `ZenohClient` starter kit from the parent
  module (backed by `zenoh-java-1.9.0`'s bundled Rust JNI). Fat jars
  are ~30 MB and include the native libraries.
- **Pure-Java samples** use the sibling
  [`pure-java/`](../pure-java/) module (zero runtime deps, JDK 17
  stdlib only). Fat jars are 90–160 KB. They need a one-time
  `mvn install` of the pure-Java module first — see
  [Building pure-Java samples](#building-pure-java-samples).

## JNI samples (using the `ZenohClient` starter kit)

| Sample                    | What it shows                                              |
|---------------------------|------------------------------------------------------------|
| [`hello-publisher/`](hello-publisher/)             | Absolute minimum: connect, publish one message, close. |
| [`hello-subscriber/`](hello-subscriber/)           | Companion subscriber that prints payloads to stdout. |
| [`json-publisher/`](json-publisher/)               | Structured JSON payloads + per-subkey publisher cache. |
| [`mtls-publisher/`](mtls-publisher/)               | TLS + mutual authentication (client cert + key). |
| [`cot-streaming-publisher/`](cot-streaming-publisher/) | Background-thread streaming, configurable TTL / rate / tracks, small CoT XML payloads. |

## Pure-Java samples (using `pure-java/` — zero runtime deps)

| Sample                    | What it shows                                              |
|---------------------------|------------------------------------------------------------|
| [`pure-java-simple-publisher/`](pure-java-simple-publisher/) | Minimum-viable pure-Java publisher. Pair with `pure-java-simple-subscriber/`. |
| [`pure-java-simple-subscriber/`](pure-java-simple-subscriber/) | Minimum-viable pure-Java subscriber with wildcard key support. |
| [`pure-java-mtls-publisher/`](pure-java-mtls-publisher/) | Drop-in sibling of the JNI `mtls-publisher` — same positional args, TLS + mTLS via PEM or PKCS12. |
| [`pure-java-mtls-subscriber/`](pure-java-mtls-subscriber/) | mTLS-authenticated pure-Java subscriber. Pair with the mTLS publisher for an end-to-end secure demo. |
| [`pure-java-scout/`](pure-java-scout/) | UDP-multicast discovery of Zenoh routers / peers on the local segment. Live ANSI table or `--json` stream. Never opens a session. |
| [`pure-java-list-topics/`](pure-java-list-topics/) | One-shot lister for the subscriber topics currently known to a router. Table by default or `--json` for pipelines. |

## Building a JNI sample

```bash
cd samples/<sample-name>
mvn package
```

No setup step required. Every JNI sample resolves the parent
`io.mdudel:java-zenoh-publisher` artifact directly from
`vendor/repo/io/mdudel/java-zenoh-publisher/`, which is checked
into this repo alongside the vendored `zenoh-java` and
`kotlin-stdlib` jars. `git clone && cd samples/<name> && mvn package`
works against a fresh checkout with nothing else installed.

## Building pure-Java samples

One-time install of the pure-Java module into your local
`~/.m2/repository`:

```bash
# From the repo root:
mvn -f pure-java/pom.xml install
```

Then the sample:

```bash
cd samples/pure-java-<sample-name>
mvn package
```

> **After a `git pull`**: re-run the `mvn install` above before
> rebuilding a pure-Java sample. Otherwise you'll get
> `cannot find symbol: class PureJava<Thing>` when the pure-Java
> module has grown a new public class since your last install — the
> .m2 jar is invisible and goes stale silently. Every pure-Java
> sample's README repeats this reminder.

Pure-Java sample fat jars land in `target/pure-java-<name>-0.1.0.jar`
at 90–160 KB (no shaded natives). They run on any JDK 17+ on any
architecture.

If you are actively editing the starter kit itself, rebuild the
parent from the repo root (`mvn package`) to refresh the vendored
copy - it is auto-copied into `vendor/repo/` as part of the parent
build's `package` phase.

### Troubleshooting: stale Maven negative cache

If you built a sample **before** the vendored parent jar existed
(pre-`be13ee8` on any old checkout), Maven negatively-cached the
lookup in your local `~/.m2/repository` and may keep refusing to
see the vendored jar even after `git pull`. Symptom:

```
Failure to find io.mdudel:java-zenoh-publisher:jar:0.1.0 in
file://.../vendor/repo was cached in the local repository,
resolution will not be reattempted until the update interval of
local-vendor has elapsed or updates are forced
```

The sample poms now set `<updatePolicy>always</updatePolicy>` on
the `local-vendor` repo so this shouldn't happen going forward.
If you hit it anyway, force one refresh with `-U`:

```bash
cd samples/<sample-name>
mvn -U clean package
```

Or if `-U` doesn't clear it either, nuke the negative-cache marker
directly:

```bash
rm -rf ~/.m2/repository/io/mdudel/java-zenoh-publisher
mvn clean package
```

Produces `target/<sample-name>-0.1.0.jar`, a ~30 MB self-contained
fat jar including the native Zenoh libraries and everything else the
sample needs.

## Running a sample

```bash
java -jar target/<sample-name>-0.1.0.jar [flags...]
```

Each sample's own `README.md` documents its flags and gives an
end-to-end demo. The class-level Javadoc at the top of every
`.java` source file has the same recipe.

## Prerequisites

- **JDK 17+** (`java -version`)
- **Maven 3.8+** (`mvn -v`)
- A **Zenoh router** to talk to. The samples default to
  `tcp/localhost:7447`. Easiest way to spin one up locally:
  ```bash
  docker run --rm -p 7447:7447 -p 8000:8000 eclipse/zenoh:1.9.0
  ```
  Or grab a native binary from
  https://github.com/eclipse-zenoh/zenoh/releases.

### Running `zenohd` locally? Start it with `--listen tcp/0.0.0.0:7447`

Every sample in this folder defaults to `tcp/localhost:7447`, but a
vanilla `zenohd` / `zenohd.exe` invocation does **not** bind
loopback by default. On startup it enumerates every network
interface with a routable address and binds those instead — you'll
see a log block like this:

```
Zenoh can be reached at: tcp/[fe80::...]:7447   (link-local IPv6)
Zenoh can be reached at: tcp/10.10.20.3:7447    (private IPv4)
Zenoh can be reached at: tcp/192.168.1.85:7447  (your LAN IP)
...
```

Notice what's missing: `127.0.0.1` and `[::1]`. If you try to run
one of the samples against this router with its default endpoint,
the socket connect to `localhost:7447` will fail with
`Connection refused` even though `zenohd` is very much alive on
the listed addresses.

Easiest fix, works on Windows / Linux / macOS:

```bash
zenohd --listen tcp/0.0.0.0:7447
# or on Windows:
zenohd.exe --listen tcp/0.0.0.0:7447
```

`0.0.0.0` binds every IPv4 interface **including** loopback, so
`tcp/localhost:7447` starts working immediately and the samples
can run with all their default arguments. If you also want IPv6
reachability, add a second listener: `--listen tcp/[::]:7447`.

If you'd rather not remember the flag every time, drop a small
JSON5 config next to the binary and pass it with `-c`:

```json5
// zenohd.json5
{
  listen: {
    endpoints: [ "tcp/0.0.0.0:7447" ]
  }
}
```

Then `zenohd -c zenohd.json5`. The Docker image above already
binds `0.0.0.0` via the `-p` port publish, so this only bites the
bare-metal `zenohd` case.

Second option, no router-config change: pass one of the addresses
from the log to the sample as its first positional argument, e.g.

```bash
java -jar target/pure-java-simple-publisher-0.1.0.jar tcp/192.168.1.85:7447
```

## End-to-end demo

Three shells, one of each pair of samples:

```bash
# shell 1 - router
docker run --rm -p 7447:7447 eclipse/zenoh:1.9.0

# shell 2 - subscriber (Ctrl-C to stop)
cd samples/hello-subscriber && mvn -q package
java -jar target/hello-subscriber-0.1.0.jar

# shell 3 - publisher (fire it as many times as you like)
cd samples/hello-publisher && mvn -q package
java -jar target/hello-publisher-0.1.0.jar
```

The subscriber should print:

```
[HelloSubscriber] listening key=demo/** via tcp/localhost:7447 (Ctrl-C to stop)
[HelloSubscriber] demo/greeting -> hello, zenoh from java!
```

## Layout of a sample

Every sample follows the same shape:

```
samples/<sample-name>/
  pom.xml                                        Maven build, shade fat-jar
  README.md                                      what it shows + how to run
  src/main/java/io/mdudel/zenoh/samples/…/       one Java class
```

Package roots are chosen per-sample under `io.mdudel.zenoh.samples`
so nothing collides on the classpath if you drop several sample jars
into the same directory.

## Adding your own sample

1. Copy an existing sample folder as a template.
2. Rename the folder, `<artifactId>`, and `<main.class>` in
   `pom.xml`.
3. Move your `.java` file(s) into
   `src/main/java/io/mdudel/zenoh/samples/<yourpkg>/`.
4. `mvn package` from your sample folder.

No changes needed to the parent `pom.xml` - samples are deliberately
not part of the reactor build so each one stays self-contained and
independently understandable.
