# pure-java-list-topics

One-shot lister for the Zenoh subscriber topics currently known to a
router. Connects, sends a `CURRENT`-mode `INTEREST` asking for the
router's subscriber state, prints the reply as either a plain table
or a compact JSON array, and exits. Handy for probing what a router
is aware of before wiring in a real subscriber, or for feeding a
monitoring pipeline via `--json` and `jq`.

**Zero third-party runtime dependencies.** JDK 17 only.

Sibling of [`pure-java-simple-publisher/`](../pure-java-simple-publisher/),
[`pure-java-simple-subscriber/`](../pure-java-simple-subscriber/), and
[`pure-java-scout/`](../pure-java-scout/). Together the four cover
publish / subscribe / router-discovery / topic-discovery from a single
zero-deps codebase.

## What "topic" actually means here

Zenoh 1.x has one wire-level discoverable primitive: the
`DeclareSubscriber` message, which peers send to the router to
register interest in a key expression. This sample asks the router
for the full set of those declarations. So a "topic" in the output
is really "a key expression that some peer has subscribed to,
that the router currently knows about".

There is deliberately no `DeclarePublisher` in the Zenoh 1.x wire
protocol — publishers are pure client-side state, and the router
only ever routes messages to matching subscribers. That means a
key expression that has active publishers but no subscribers will
**not** show up in this listing. If you need to catch traffic from
publishers directly, the honest answer is to subscribe to `**` with
[`pure-java-simple-subscriber`](../pure-java-simple-subscriber/) and
observe the keys arriving on `Sample.key()`.

## Build

The pure-Java module lives at `../../pure-java/` in this repo.
One-time install into your local `~/.m2/repository`:

```bash
# From the repo root:
mvn -f pure-java/pom.xml install
```

> **After a `git pull`**: re-run the above `mvn install` before
> rebuilding this sample. Otherwise you'll get
> `cannot find symbol: class Topic` (or similar) when the pure-Java
> module has grown a new public class since your last install — the
> `.m2` jar is invisible and goes stale silently.

Then this sample:

```bash
cd samples/pure-java-list-topics
mvn package
```

Produces `target/pure-java-list-topics-0.1.0.jar` — a runnable fat
jar around 95 KB.

## Run

```bash
# Defaults: tcp/localhost:7447, pattern="**", 3s timeout, plain table
java -jar target/pure-java-list-topics-0.1.0.jar

# Same, but emit JSON to stdout (ready to pipe into jq or a file)
java -jar target/pure-java-list-topics-0.1.0.jar --json

# Restrict to a pattern, longer timeout for a slow / distant router
java -jar target/pure-java-list-topics-0.1.0.jar tcp/router.local:7447 'sensors/**' 10000

# JSON output for a specific pattern
java -jar target/pure-java-list-topics-0.1.0.jar --json tcp/router.local:7447 'sensors/**' 10000
```

Positional args (all optional): `<endpoint> <keyExprPattern> <timeoutMs>`.
Flags: `--json`, `-h` / `--help`.

## Sample output — table

Two subscribers active on the router at the time of the snapshot:

```
[pure-java-list-topics] endpoint=tcp/localhost:7447 pattern=** timeout=3000ms

KEY EXPRESSION      DECLARED-BY-ID
------------------------------------
sensors/room1/temp  101
sensors/room2/temp  102

[pure-java-list-topics] 2 topics
```

## Sample output — JSON

```json
[{"keyExpr":"sensors/room1/temp","declaredById":101},{"keyExpr":"sensors/room2/temp","declaredById":102}]
```

Piped through `jq` for a friendlier view:

```bash
$ java -jar target/pure-java-list-topics-0.1.0.jar --json | jq .
[
  {
    "keyExpr": "sensors/room1/temp",
    "declaredById": 101
  },
  {
    "keyExpr": "sensors/room2/temp",
    "declaredById": 102
  }
]
```

## Prove it end-to-end

Three terminals against the same router:

```bash
# Terminal 1: start a router. Docker one-liner is the fastest option.
docker run --rm -it -p 7447:7447 eclipse/zenoh

# Terminal 2: subscribe to something so there IS a declaration for
# the router to report.
java -jar ../pure-java-simple-subscriber/target/pure-java-simple-subscriber-0.1.0.jar \
    tcp/localhost:7447 'sensors/**'

# Terminal 3: list the topics.
java -jar target/pure-java-list-topics-0.1.0.jar
```

The listing in terminal 3 should include one entry for
`sensors/**` matching the subscription running in terminal 2.

## How it works (in one paragraph)

Under the hood the sample calls `PureJavaZenohSubscriber.listTopicsNow(pattern, timeoutMs)`,
which sends an `INTEREST` in `CURRENT` mode with the `OPT_SUBSCRIBERS`
option set, blocks the caller until a `FINAL` sentinel arrives (or
the timeout elapses), and returns an immutable `List<Topic>` of the
`DeclareSubscriber` records the router replied with. `Topic` is a
tiny record — `keyExpr` and `declaredById` — with a `toJson(...)`
static helper that serialises the list to a compact JSON array
using only JDK APIs. There are no third-party dependencies.
