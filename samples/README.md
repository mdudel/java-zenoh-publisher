# samples/

Independently buildable examples that use the `ZenohClient` starter
kit. Each subfolder is a complete Maven project: `cd` into it,
`mvn package`, and you have a runnable fat jar.

| Sample                    | What it shows                                              |
|---------------------------|------------------------------------------------------------|
| [`hello-publisher/`](hello-publisher/)             | Absolute minimum: connect, publish one message, close. |
| [`hello-subscriber/`](hello-subscriber/)           | Companion subscriber that prints payloads to stdout. |
| [`json-publisher/`](json-publisher/)               | Structured JSON payloads + per-subkey publisher cache. |
| [`mtls-publisher/`](mtls-publisher/)               | TLS + mutual authentication (client cert + key). |
| [`cot-streaming-publisher/`](cot-streaming-publisher/) | Background-thread streaming, configurable TTL / rate / tracks, elliptical paths, small CoT XML payloads. |

## Building a sample

```bash
cd samples/<sample-name>
mvn package
```

No setup step required. Every sample resolves the parent
`io.mdudel:java-zenoh-publisher` artifact directly from
`vendor/repo/io/mdudel/java-zenoh-publisher/`, which is checked
into this repo alongside the vendored `zenoh-java` and
`kotlin-stdlib` jars. `git clone && cd samples/<name> && mvn package`
works against a fresh checkout with nothing else installed.

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
