# Sample TCP Smoke Test Steps



# Start `zenohd` and verify it is listening

Open **PowerShell** (rather than Command Prompt).

## Confirm the binary works

```powershell
D:\ZENOH\zenohd.exe --version
```

Expected output:

```text
zenohd 1.x.x
```

If the command fails, Windows Defender or SmartScreen may have blocked the executable. Open the file's **Properties**, select **Unblock** (if present), then retry.

## Start `zenohd` in the foreground

```powershell
D:\ZENOH\zenohd.exe --listen tcp/0.0.0.0:7447
```

Expected log output:

```text
INFO ThreadId(01) zenohd: zenohd v1.x.x
INFO ThreadId(01) zenoh::net::runtime: Zenoh Rt version: v1.x.x
INFO ThreadId(01) zenoh::net::runtime::orchestrator: Zenoh can be reached at: tcp/<host-ip>:7447
```

Leave this terminal running. All subsequent steps connect to this router.

## Verify TCP is listening (second PowerShell window)

```powershell
Test-NetConnection -ComputerName localhost -Port 7447
```

Expected result:

```text
TcpTestSucceeded : True
```

If the result is `False`, either `zenohd` has not completed startup or Windows Firewall blocked the application. Allow the firewall prompt when `zenohd` runs for the first time.

# Test `hello-publisher` and `hello-subscriber` (JNI baseline)

This test verifies the complete JNI-based environment. Both applications use the Rust `zenoh-java` binding, so successful execution confirms that the JDK, Maven, firewall configuration, and `zenohd` are functioning correctly. Running this test before the pure-Java sample helps isolate environmental issues.

Three PowerShell windows are required:

- **Terminal A** — `zenohd` (already running)
- **Terminal B** — subscriber
- **Terminal C** — publisher

## Confirm the development environment

In **Terminal B**:

```powershell
java -version
mvn -version
```

Expected versions:

- **JDK 17 or later**
- **Maven 3.6 or later**

If either component is missing:

```powershell
winget install EclipseAdoptium.Temurin.17.JDK
winget install Apache.Maven
```

## Clone the repository (if needed)

```powershell
cd D:\
git clone https://github.com/mdudel/java-zenoh-publisher.git
cd java-zenoh-publisher
```

For an existing local clone:

```powershell
git pull
```

Verify that the repository is up to date:

```powershell
git log --oneline -3
```

The latest commit should be `b582536` or newer.

## Build the subscriber (Terminal B)

```powershell
cd D:\java-zenoh-publisher\samples\hello-subscriber
mvn -B package
```

The first build downloads dependencies from Maven Central and may require 30–90 seconds depending on network speed.

A warning referencing `dependency-reduced-pom.xml` is expected and can be ignored.

The build should end with:

```text
BUILD SUCCESS
```

Verify that the JAR was created:

```powershell
dir target\hello-subscriber-*.jar
```

A single JAR should exist in `target/`, approximately 30 MB in size.

## Start the subscriber (Terminal B)

```powershell
java -jar target\hello-subscriber-0.1.0.jar
```

Expected output:

```text
[HelloSubscriber] listening key=demo/** via tcp/localhost:7447 (Ctrl-C to stop)
```

Leave this terminal running. One line will be printed for each received message.

## Build and run the publisher (Terminal C)

```powershell
cd D:\java-zenoh-publisher\samples\hello-publisher
mvn -B package
java -jar target\hello-publisher-0.1.0.jar
```

Expected output:

```text
[HelloPublisher] published 23B to key=demo/greeting via tcp/localhost:7447
```

The application should exit normally.

## Verify subscriber output (Terminal B)

Expected output after the publisher completes:

```text
[HelloSubscriber] demo/greeting -> hello, zenoh from java!
```

This confirms successful end-to-end communication:

```text
publisher → JNI → zenohd → JNI → subscriber
```

After verifying the message, stop the subscriber with **Ctrl+C**.

# Test `pure-java-simple-publisher`

Use the same three terminals:

- **Terminal A** — `zenohd`
- **Terminal B** — subscriber
- **Terminal C** — pure-Java publisher

## Install the pure-Java module (one-time setup)

The sample resolves `io.mdudel:java-zenoh-publisher-pure` from the local Maven repository.

In **Terminal C**:

```powershell
cd D:\DEV\PROJECTS\Zenoh\java-zenoh-publisher
mvn -B -f pure-java\pom.xml install
```

Expected actions:

- Download JUnit (first execution only)
- Execute approximately 225 tests
- Install `java-zenoh-publisher-pure-0.0.1-SNAPSHOT.jar` into:

```text
%USERPROFILE%\.m2\repository\io\mdudel\java-zenoh-publisher-pure\0.0.1-SNAPSHOT\
```

The build should finish with:

```text
BUILD SUCCESS
```

## Build the pure-Java sample

```powershell
cd D:\DEV\PROJECTS\Zenoh\java-zenoh-publisher\samples\pure-java-simple-publisher
mvn -B package
```

The build should complete quickly and finish with:

```text
BUILD SUCCESS
```

Verify the generated JAR:

```powershell
dir target\pure-java-simple-publisher-*.jar
```

Expected size:

- Approximately **90 KB**

This size difference highlights the absence of bundled native JNI libraries.

## Run the pure-Java publisher

```powershell
java -jar target\pure-java-simple-publisher-0.1.0.jar
```

Default configuration:

- Endpoint: `tcp/localhost:7447`
- Key: `demo/greeting`
- Messages: **5**
- Interval: **1000 ms**

Expected output:

```text
[pure-java-simple-publisher] endpoint=tcp/localhost:7447 key=demo/greeting count=5 intervalMs=1000
Jul 10, 2026 4:15:30 PM io.mdudel.zenoh.purejava.PureJavaZenohPublisher start
INFO: PureJavaZenohPublisher.start() endpoint=tcp/localhost:7447 key=demo/greeting effectiveKey=demo/greeting org= verifyHostname=true lease=10,000ms
[pure-java-simple-publisher] session OPEN
[pure-java-simple-publisher] 1/5 -> 'hello #1 from pure-Java' (sent=1)
[pure-java-simple-publisher] 2/5 -> 'hello #2 from pure-Java' (sent=2)
[pure-java-simple-publisher] 3/5 -> 'hello #3 from pure-Java' (sent=3)
[pure-java-simple-publisher] 4/5 -> 'hello #4 from pure-Java' (sent=4)
[pure-java-simple-publisher] 5/5 -> 'hello #5 from pure-Java' (sent=5)
[pure-java-simple-publisher] done, closing
```

## Verify subscriber output (Terminal B)

Five new messages should appear:

```text
[HelloSubscriber] listening key=demo/** via tcp/localhost:7447 (Ctrl-C to stop)
[HelloSubscriber] demo/greeting -> hello #1 from pure-Java
[HelloSubscriber] demo/greeting -> hello #2 from pure-Java
[HelloSubscriber] demo/greeting -> hello #3 from pure-Java
[HelloSubscriber] demo/greeting -> hello #4 from pure-Java
[HelloSubscriber] demo/greeting -> hello #5 from pure-Java
```

Successful reception confirms that the JNI subscriber correctly decodes frames generated by the pure-Java encoder, demonstrating byte-level compatibility with the Zenoh wire protocol.