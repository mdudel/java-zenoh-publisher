# json-publisher

Publishes 5 structured JSON payloads on per-subkey routes. Shows two
patterns:

1. **Layering a serializer** over `ZenohClient.publish(byte[])`
   without modifying the client. The sample hand-rolls JSON to stay
   dependency-free; in real code you'd use Jackson / Gson.
2. **Per-subkey publisher cache**: each `publish(subKey, bytes)`
   call routes to `baseKey/subKey`, and the underlying
   `io.zenoh.pubsub.Publisher` for that sub-key is declared lazily
   and cached inside `ZenohClient` for reuse.

Scenario: a handful of environment sensors publishing temperature /
humidity / battery readings.

## Build

```bash
cd samples/json-publisher
mvn package
```

The build resolves the starter kit directly from the vendored
`vendor/repo/` alongside the other Zenoh dependencies - no
top-level install step required.

## Run

```bash
# default: connect to tcp/localhost:7447, publish to demo/sensors/sensor-<0..4>
java -jar target/json-publisher-0.1.0-fat.jar

# override endpoint and base key:
java -jar target/json-publisher-0.1.0-fat.jar tcp/router.local:7447 my/sensors
```

## Sample output

```
[JsonPublisher] demo/sensors/sensor-0 {"sensorId":"sensor-0","temperatureC":21.5,"humidityPct":45.0,"batteryPct":90,"ts":1723456789012}
[JsonPublisher] demo/sensors/sensor-1 {"sensorId":"sensor-1","temperatureC":21.8,...}
...
[JsonPublisher] sent=5
```
