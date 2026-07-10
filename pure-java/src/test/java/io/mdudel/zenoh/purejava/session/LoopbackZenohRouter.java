/*
 * Copyright 2026 the java-zenoh-publisher-pure contributors.
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 */
package io.mdudel.zenoh.purejava.session;

import io.mdudel.zenoh.purejava.transport.StreamFramer;
import io.mdudel.zenoh.purejava.wire.Extension;
import io.mdudel.zenoh.purejava.wire.WBuf;
import io.mdudel.zenoh.purejava.wire.WhatAmI;
import io.mdudel.zenoh.purejava.wire.ZenohId;
import io.mdudel.zenoh.purejava.wire.messages.Close;
import io.mdudel.zenoh.purejava.wire.messages.Frame;
import io.mdudel.zenoh.purejava.wire.messages.Init;
import io.mdudel.zenoh.purejava.wire.messages.KeepAlive;
import io.mdudel.zenoh.purejava.wire.messages.Open;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Minimal test-only Zenoh router that speaks the client-side handshake
 * enough to validate {@link ZenohSession} end to end. Uses the same
 * {@link StreamFramer} the client does, and reuses our own wire codecs
 * (Init/Open/KeepAlive/Close/Frame) so any encode/decode drift shows up
 * as a handshake failure or a bad-batch exception here.
 *
 * <p>Not production-fit &mdash; only implements what the pure-Java
 * publisher exercises:</p>
 * <ul>
 *   <li>Accept a plain TCP connection on 127.0.0.1 (ephemeral port).</li>
 *   <li>Read InitSyn, reply with InitAck (server-generated cookie).</li>
 *   <li>Read OpenSyn (verify cookie echo), reply with OpenAck at the
 *       client-proposed lease (or an override).</li>
 *   <li>Collect subsequent Frame / KeepAlive / Close messages into an
 *       inspectable queue.</li>
 * </ul>
 */
final class LoopbackZenohRouter implements AutoCloseable {

    /** Overrides used to script server-side behaviour per test. */
    static final class Behaviour {
        long   leaseOverrideMs = -1;         // -1 = accept client's proposed lease
        int    handshakeStallMs = 0;         // artificial delay before InitAck
        boolean sendGarbageInitAck = false;  // reply with an invalid InitAck
        Consumer<ClientSession> onOpened;    // extra scripted behaviour once OPEN
    }

    private final ServerSocket           server;
    private final Behaviour              behaviour;
    private final Thread                 acceptThread;
    private final ZenohId                serverId = ZenohId.random();
    private final CopyOnWriteArrayList<ClientSession> sessions = new CopyOnWriteArrayList<>();
    private final AtomicBoolean          closed = new AtomicBoolean(false);
    private final AtomicInteger          cookieSeq = new AtomicInteger(0);

    static LoopbackZenohRouter bind() throws IOException {
        return new LoopbackZenohRouter(new Behaviour());
    }

    static LoopbackZenohRouter bind(Behaviour b) throws IOException {
        return new LoopbackZenohRouter(b);
    }

    private LoopbackZenohRouter(Behaviour b) throws IOException {
        this.behaviour = b;
        this.server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
        this.acceptThread = new Thread(this::acceptLoop, "loopback-zenoh-accept");
        this.acceptThread.setDaemon(true);
        this.acceptThread.start();
    }

    int port() { return server.getLocalPort(); }
    ZenohId serverId() { return serverId; }
    List<ClientSession> sessions() { return List.copyOf(sessions); }

    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        try { server.close(); } catch (IOException ignored) {}
        for (ClientSession cs : sessions) cs.close();
    }

    private void acceptLoop() {
        try {
            while (!closed.get()) {
                Socket sock;
                try { sock = server.accept(); }
                catch (IOException e) { if (closed.get()) return; throw new RuntimeException(e); }
                ClientSession cs = new ClientSession(sock);
                sessions.add(cs);
                new Thread(cs::run, "loopback-zenoh-session-" + cs.hashCode()).start();
            }
        } catch (RuntimeException ignored) {}
    }

    /** One accepted client connection. */
    final class ClientSession implements AutoCloseable {
        private final Socket           socket;
        private final AtomicBoolean    csClosed = new AtomicBoolean(false);
        private volatile OutputStream  out;
        private volatile ZenohId       clientId;
        private volatile byte[]        cookie;

        /** Every non-handshake batch received from the client, decoded by header id. */
        final LinkedBlockingQueue<Batch> received = new LinkedBlockingQueue<>();

        ClientSession(Socket s) { this.socket = s; }

        void run() {
            try {
                InputStream  in  = socket.getInputStream();
                OutputStream os  = socket.getOutputStream();
                this.out = os;

                // 1. Read InitSyn
                byte[] b1 = StreamFramer.readFrame(in);
                if (behaviour.handshakeStallMs > 0) {
                    try { Thread.sleep(behaviour.handshakeStallMs); }
                    catch (InterruptedException ie) { Thread.currentThread().interrupt(); return; }
                }
                if (behaviour.sendGarbageInitAck) {
                    StreamFramer.writeFrame(os, new byte[] { 0x00, 0x00, 0x00 });
                    os.flush();
                    return;
                }
                Init.InitSyn syn;
                try { syn = decodeInitSyn(b1); }
                catch (RuntimeException e) { return; }
                this.clientId = syn.zid();
                this.cookie = ("cookie-" + cookieSeq.incrementAndGet())
                        .getBytes(java.nio.charset.StandardCharsets.US_ASCII);

                // 2. Send InitAck
                StreamFramer.writeFrame(os, encodeInitAck(syn.version(), serverId, cookie));
                os.flush();

                // 3. Read OpenSyn
                byte[] b2 = StreamFramer.readFrame(in);
                Open.OpenSyn openSyn;
                try { openSyn = decodeOpenSyn(b2); }
                catch (RuntimeException e) { return; }
                if (!java.util.Arrays.equals(openSyn.cookie(), cookie)) {
                    // Cookie mismatch = protocol violation; drop the connection.
                    return;
                }

                // 4. Send OpenAck
                long lease = behaviour.leaseOverrideMs >= 0
                        ? behaviour.leaseOverrideMs
                        : openSyn.leaseMillis();
                StreamFramer.writeFrame(os, encodeOpenAck(lease, openSyn.initialSn()));
                os.flush();

                // Optional scripted behaviour once we're OPEN.
                if (behaviour.onOpened != null) {
                    Thread t = new Thread(() -> behaviour.onOpened.accept(this),
                            "loopback-zenoh-onOpened");
                    t.setDaemon(true);
                    t.start();
                }

                // Loop: consume post-handshake batches until close.
                while (!csClosed.get()) {
                    byte[] batch;
                    try { batch = StreamFramer.readFrame(in); }
                    catch (java.io.EOFException eof) { return; }
                    if (batch.length == 0) continue;
                    int id = batch[0] & 0x1F;
                    received.offer(new Batch(id, batch));
                    // Reply-driven server keep-alive: echo a KEEP_ALIVE back on every client KA
                    // so the client's lastRecvNanos advances too. Real routers behave similarly.
                    if (id == KeepAlive.ID) {
                        try {
                            synchronized (os) {
                                StreamFramer.writeFrame(os, KeepAlive.EMPTY.encode());
                                os.flush();
                            }
                        } catch (IOException ignored) { return; }
                    }
                    if (id == Close.ID) return;
                }
            } catch (IOException ignored) {
            } finally {
                close();
            }
        }

        /** Emit a server-driven frame from a test. */
        void sendRaw(byte[] batch) throws IOException {
            OutputStream os = out;
            if (os == null) throw new IOException("session not open");
            synchronized (os) {
                StreamFramer.writeFrame(os, batch);
                os.flush();
            }
        }

        void sendKeepAlive() throws IOException {
            sendRaw(KeepAlive.EMPTY.encode());
        }

        void sendClose() throws IOException {
            sendRaw(Close.sessionGeneric().encode());
        }

        ZenohId clientId() { return clientId; }

        @Override public void close() {
            if (!csClosed.compareAndSet(false, true)) return;
            try { socket.close(); } catch (IOException ignored) {}
        }
    }

    /** Immutable record of a received batch: (header id nibble, raw bytes). */
    record Batch(int id, byte[] bytes) {}

    // ---- server-side wire helpers (kept minimal, only what tests need) ---

    /** Decode InitSyn as a mirror of our client-side encoder. */
    private static Init.InitSyn decodeInitSyn(byte[] bytes) {
        // Header byte
        int header = bytes[0] & 0xFF;
        if ((header & 0x1F) != Init.ID) throw new IllegalArgumentException("not INIT");
        if ((header & Init.FLAG_A) != 0) throw new IllegalArgumentException("A flag set (InitAck?)");
        boolean hasZ = (header & Init.FLAG_Z) != 0;
        int version = bytes[1] & 0xFF;
        int lenWai  = bytes[2] & 0xFF;
        int lenNib  = (lenWai >>> 4) & 0x0F;
        WhatAmI wai = WhatAmI.fromBits(lenWai & 0x03);
        int zidLen  = lenNib + 1;
        byte[] zidBytes = new byte[zidLen];
        System.arraycopy(bytes, 3, zidBytes, 0, zidLen);
        ZenohId zid = new ZenohId(zidBytes);
        List<Extension> exts = hasZ
                ? Extension.readAll(new io.mdudel.zenoh.purejava.wire.RBuf(
                        bytes, 3 + zidLen, bytes.length - 3 - zidLen))
                : List.of();
        return new Init.InitSyn(version, wai, zid, exts);
    }

    /** Encode an InitAck matching the client's expectations. */
    private static byte[] encodeInitAck(int version, ZenohId zid, byte[] cookie) {
        WBuf w = new WBuf(32 + cookie.length);
        int header = Init.ID | Init.FLAG_A;   // A=1 (ACK), S=0 (no size negotiation), Z=0
        w.u8(header);
        w.u8(version);
        int lenNibble = zid.encodedLenNibble();
        int lenWai = ((lenNibble & 0x0F) << 4) | (WhatAmI.ROUTER.bits & 0x03);
        w.u8(lenWai);
        w.bytes(zid.bytes());
        w.lenBytes(cookie);
        return w.toByteArray();
    }

    /** Decode OpenSyn as a mirror of our client-side encoder. */
    private static Open.OpenSyn decodeOpenSyn(byte[] bytes) {
        io.mdudel.zenoh.purejava.wire.RBuf r = new io.mdudel.zenoh.purejava.wire.RBuf(bytes);
        int header = r.u8();
        if ((header & 0x1F) != Open.ID) throw new IllegalArgumentException("not OPEN");
        if ((header & Open.FLAG_A) != 0) throw new IllegalArgumentException("A flag set (OpenAck?)");
        boolean hasZ = (header & Open.FLAG_Z) != 0;
        long leaseMs   = r.varInt();
        long initialSn = r.varInt();
        byte[] cookie  = r.lenBytes();
        List<Extension> exts = hasZ ? Extension.readAll(r) : List.of();
        return new Open.OpenSyn(leaseMs, initialSn, cookie, exts);
    }

    /** Encode an OpenAck (T=0 ms) at the given lease. */
    private static byte[] encodeOpenAck(long leaseMs, long initialSn) {
        WBuf w = new WBuf(16);
        int header = Open.ID | Open.FLAG_A;  // A=1, T=0 (ms), Z=0
        w.u8(header);
        w.varInt(leaseMs);
        w.varInt(initialSn);
        return w.toByteArray();
    }
}
