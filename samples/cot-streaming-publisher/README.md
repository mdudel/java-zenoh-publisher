# cot-streaming-publisher

Streaming publisher. Establishes a single `ZenohClient` and starts
a background scheduler that publishes a stream of CoT XML events
until the configured TTL expires (or the user hits Ctrl-C).

Every tick, each simulated track walks one step around a randomly
generated elliptical path and a small CoT 2.0 XML event is emitted
on that track's own sub-key (`baseKey/<uid>`), exercising the
per-subkey publisher cache inside `ZenohClient`.

## Build

```bash
cd samples/cot-streaming-publisher
mvn package
```

The build resolves the starter kit directly from the vendored
`vendor/repo/` alongside the other Zenoh dependencies - no
top-level install step required.

## Run

```bash
# defaults: tcp/localhost:7447, key demo/cot, 3 tracks, 1 Hz per track, 30 s TTL
java -jar target/cot-streaming-publisher-0.1.0.jar

# 20 tracks, 5 Hz each, run for 2 minutes:
java -jar target/cot-streaming-publisher-0.1.0.jar \
      --endpoint=tcp/router.local:7447 \
      --key=demo/cot \
      --tracks=20 \
      --rate=5 \
      --ttl-seconds=120
```

## Flags

| Flag | Default | Notes |
|------|---------|-------|
| `--endpoint`     | `tcp/localhost:7447` | Zenoh router endpoint |
| `--key`          | `demo/cot`           | Base key; each track publishes to `<key>/<uid>` |
| `--tracks`       | `3` (max `1000`)     | Number of simulated tracks |
| `--rate`         | `1.0`                | Publishes per second **per track** |
| `--ttl-seconds`  | `30`                 | Runtime before clean shutdown |
| `--centre-lat`   | `50.0`               | Bounding-box centre latitude |
| `--centre-lon`   | `10.0`               | Bounding-box centre longitude |
| `--span-deg`     | `2.0`                | Bounding-box side length in degrees |
| `--seed`         | current millis       | RNG seed for repeatable runs |
| `--help`         |                      | Print usage and exit |

## CoT payload shape

Each publish is a minimal CoT 2.0 event:

```xml
<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<event version="2.0" uid="TRK-0007" type="a-f-G-U-C"
       time="2026-07-09T14:27:00Z" start="..." stale="..." how="m-g">
  <point lat="50.440445" lon="10.353081" hae="3529.65"
         ce="9999999.0" le="9999999.0"/>
  <detail><contact callsign="TRK-0007"/></detail>
</event>
```

`ce` / `le` = `9999999.0` are the CoT sentinel values for "position
error unknown". Swap `type` for whatever fits your scenario -
`a-f-G-U-C` is the public MIL-STD-2525 friendly-ground-unit-combat
code, chosen because it's the canonical CoT example type.

## Shutdown behaviour

TTL expiry and Ctrl-C hit the same code path: stop new ticks, wait
up to 3 s for the in-flight tick to complete, close the Zenoh
session via try-with-resources, print a summary of `sent=/failed=`
counts, exit cleanly.

## Threading model

One shared `ZenohClient`, one single-threaded
`ScheduledExecutorService` (daemon). All publishes happen on the
scheduler thread, so no user-facing locking is required. Scale up
`--tracks` or `--rate` and the pattern stays the same.
