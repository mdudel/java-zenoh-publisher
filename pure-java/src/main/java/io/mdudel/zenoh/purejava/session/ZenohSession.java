/*
 * Copyright 2026 the java-zenoh-publisher-pure contributors.
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * Clean-room pure-Java implementation of the Eclipse Zenoh 1.x wire protocol.
 */
package io.mdudel.zenoh.purejava.session;

import io.mdudel.zenoh.purejava.transport.Transport;
import io.mdudel.zenoh.purejava.transport.TransportException;
import io.mdudel.zenoh.purejava.wire.Encoding;
import io.mdudel.zenoh.purejava.wire.ZenohId;
import io.mdudel.zenoh.purejava.wire.messages.Close;
import io.mdudel.zenoh.purejava.wire.messages.Frame;
import io.mdudel.zenoh.purejava.wire.messages.Init;
import io.mdudel.zenoh.purejava.wire.messages.KeepAlive;
import io.mdudel.zenoh.purejava.wire.messages.Open;
import io.mdudel.zenoh.purejava.wire.messages.Push;
import io.mdudel.zenoh.purejava.wire.messages.Put;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A Zenoh 1.x client session over any {@link Transport}.
 *
 * <p>Owns:</p>
 * <ul>
 *   <li>The 4-message handshake ceremony (InitSyn / InitAck /
 *       OpenSyn / OpenAck) &mdash; see {@link #open()}.</li>
 *   <li>The reader thread that consumes inbound frames after handshake
 *       and dispatches KEEP_ALIVE / CLOSE server-driven messages.</li>
 *   <li>The KEEP_ALIVE scheduler (fires at {@code lease/4} intervals
 *       when the outbound side has been quiet).</li>
 *   <li>The lease-expiry watchdog (tears down if the inbound side has
 *       been quiet for a full lease period).</li>
 *   <li>Publish serialisation over the transport (transport already
 *       serialises {@code send()}, but the session adds a monotonic
 *       sequence number per frame).</li>
 * </ul>
 *
 * <p>The transport passed in is <b>owned</b> by the session after
 * {@link #open()}: closing the session closes the transport. The
 * transport is <b>not</b> connected by the session &mdash; call
 * {@link Transport#connect()} beforehand, or use
 * {@link Builder#autoConnect(boolean)} to let the session do it.</p>
 *
 * <p>Publish-only client SPI in the MVP: {@link #publish(String, byte[])} and
 * variants. Subscribe / query are out of scope for the publisher.</p>
 *
 * <p>Threading:</p>
 * <ul>
 *   <li>{@link #open()} is single-threaded (call from one thread once).</li>
 *   <li>{@link #publish(String, byte[])} is safe from any thread; frames
 *       serialise cleanly on the underlying transport lock.</li>
 *   <li>{@link #close()} is idempotent and safe from any thread.</li>
 * </ul>
 */
public final class ZenohSession implements AutoCloseable {

    private static final Logger LOG = System.getLogger(ZenohSession.class.getName());

    // ---- protocol constants ----------------------------------------------

    /** Zenoh wire protocol version 1.x uses this byte in the InitSyn/InitAck version field. */
    public static final int  WIRE_VERSION           = 0x09;

    /** Default lease we propose in OpenSyn (ms). */
    public static final long DEFAULT_LEASE_MS       = 10_000L;

    /** Default handshake read timeout (ms). */
    public static final int  DEFAULT_HANDSHAKE_MS   = 5_000;

    /** Default close-frame flush timeout (ms). */
    public static final int  DEFAULT_CLOSE_MS       = 1_500;

    // ---- configuration ---------------------------------------------------

    private final Transport transport;
    private final ZenohId   localId;
    private final long      proposedLeaseMs;
    private final int       handshakeTimeoutMs;
    private final int       closeTimeoutMs;
    private final boolean   autoConnect;

    // ---- lifecycle state -------------------------------------------------

    private final AtomicReference<SessionState> state = new AtomicReference<>(SessionState.CREATED);
    private volatile ZenohId                    remoteId;
    private volatile long                       negotiatedLeaseMs;
    private final AtomicLong                    outboundSn   = new AtomicLong(0);
    private final AtomicLong                    lastSendNanos = new AtomicLong(0);
    private final AtomicLong                    lastRecvNanos = new AtomicLong(0);

    private final AtomicBoolean                 closed = new AtomicBoolean(false);
    private volatile ScheduledExecutorService   scheduler;
    private volatile ScheduledFuture<?>         keepAliveTask;
    private volatile ScheduledFuture<?>         leaseWatchdogTask;
    private volatile Thread                     readerThread;

    private ZenohSession(Builder b) {
        this.transport          = b.transport;
        this.localId            = b.localId != null ? b.localId : ZenohId.random();
        this.proposedLeaseMs    = b.leaseMs;
        this.handshakeTimeoutMs = b.handshakeTimeoutMs;
        this.closeTimeoutMs     = b.closeTimeoutMs;
        this.autoConnect        = b.autoConnect;
    }

    public static Builder builder(Transport transport) {
        return new Builder(transport);
    }

    // ---- public read-only state -----------------------------------------

    public SessionState state()             { return state.get(); }
    public ZenohId      localId()           { return localId; }
    public ZenohId      remoteId()          { return remoteId; }
    public long         negotiatedLeaseMs() { return negotiatedLeaseMs; }
    public Transport    transport()        { return transport; }

    // ---- lifecycle --------------------------------------------------------

    /**
     * Perform the four-message handshake and enter {@link SessionState#OPEN}.
     * Blocks up to {@link Builder#handshakeTimeoutMs(int)} per receive step.
     *
     * @throws SessionException on any handshake failure, protocol violation,
     *                          or timeout; the session is placed in
     *                          {@link SessionState#CLOSED} before rethrow.
     */
    public void open() throws SessionException {
        if (!state.compareAndSet(SessionState.CREATED, SessionState.CONNECTING)) {
            throw new SessionException("open() may only be called once, state=" + state.get());
        }
        try {
            if (autoConnect && !transport.isOpen()) {
                transport.connect();
            } else if (!transport.isOpen()) {
                throw new SessionException(
                        "transport not connected; call transport.connect() first "
                                + "or use Builder.autoConnect(true)");
            }
            state.set(SessionState.OPENING);

            // 1. Client -> Server: InitSyn
            Init.InitSyn initSyn = new Init.InitSyn(
                    WIRE_VERSION,
                    io.mdudel.zenoh.purejava.wire.WhatAmI.CLIENT,
                    localId,
                    java.util.List.of());
            sendRaw(initSyn.encode());

            // 2. Server -> Client: InitAck
            byte[] initAckBytes = receiveExpectedFrame("InitAck");
            Init.InitAck initAck;
            try {
                initAck = Init.InitAck.decode(initAckBytes);
            } catch (RuntimeException e) {
                throw new SessionException("failed to decode InitAck: " + e.getMessage(), e);
            }
            this.remoteId = initAck.zid();

            // 3. Client -> Server: OpenSyn (echo cookie)
            Open.OpenSyn openSyn = new Open.OpenSyn(
                    proposedLeaseMs,
                    /* initialSn = */ 0L,
                    initAck.cookie(),
                    java.util.List.of());
            sendRaw(openSyn.encode());

            // 4. Server -> Client: OpenAck
            byte[] openAckBytes = receiveExpectedFrame("OpenAck");
            Open.OpenAck openAck;
            try {
                openAck = Open.OpenAck.decode(openAckBytes);
            } catch (RuntimeException e) {
                throw new SessionException("failed to decode OpenAck: " + e.getMessage(), e);
            }
            this.negotiatedLeaseMs = openAck.leaseMillis();

            // Enter OPEN and spin up the periodic tasks + reader thread.
            state.set(SessionState.OPEN);
            LOG.log(Level.DEBUG, () ->
                    "session OPEN, remote=" + remoteId + " lease=" + negotiatedLeaseMs + "ms");
            long nowNs = System.nanoTime();
            lastSendNanos.set(nowNs);
            lastRecvNanos.set(nowNs);
            startBackgroundTasks();
        } catch (SessionException se) {
            forceClosed();
            throw se;
        } catch (TransportException | InterruptedException e) {
            forceClosed();
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new SessionException("handshake failure: " + e.getMessage(), e);
        }
    }

    /**
     * Publish a payload under a key expression. Wraps the payload in
     * PUT &rarr; PUSH &rarr; FRAME and hands to the transport.
     */
    public void publish(String keyExpr, byte[] payload) throws SessionException {
        publish(keyExpr, payload, Encoding.EMPTY);
    }

    /** Publish with an explicit encoding. */
    public void publish(String keyExpr, byte[] payload, Encoding encoding) throws SessionException {
        if (state.get() != SessionState.OPEN) {
            throw new SessionException("publish requires state=OPEN, current=" + state.get());
        }
        Objects.requireNonNull(keyExpr,  "keyExpr");
        Objects.requireNonNull(payload,  "payload");
        Objects.requireNonNull(encoding, "encoding");
        Put put = new Put(null, encoding, java.util.List.of(), payload);
        Push push = Push.ofPut(keyExpr, put);
        long sn = outboundSn.getAndIncrement();
        Frame frame = Frame.ofPush(sn, /* reliable = */ true, push);
        sendRaw(frame.encode());
    }

    /** Publish a UTF-8 string with the standard string-encoding tag. */
    public void publishString(String keyExpr, String payload) throws SessionException {
        publish(keyExpr, payload.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                Encoding.of(Encoding.ID_ZENOH_STRING));
    }

    /** Publish a JSON string with the application/json encoding tag. */
    public void publishJson(String keyExpr, String json) throws SessionException {
        publish(keyExpr, json.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                Encoding.of(Encoding.ID_APPLICATION_JSON));
    }

    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        SessionState prev = state.getAndSet(SessionState.CLOSING);
        try {
            if (prev == SessionState.OPEN && transport.isOpen()) {
                // Best-effort CLOSE frame; timeout hard so a stuck peer doesn't hang shutdown.
                try {
                    sendRaw(Close.sessionGeneric().encode());
                } catch (SessionException ignored) { /* transport may already be down */ }
            }
        } finally {
            cancelBackgroundTasks();
            transport.close();
            state.set(SessionState.CLOSED);
        }
    }

    // ---- internals -------------------------------------------------------

    private void sendRaw(byte[] bytes) throws SessionException {
        try {
            transport.send(bytes);
            lastSendNanos.set(System.nanoTime());
        } catch (TransportException e) {
            throw new SessionException(
                    "transport send failed: " + e.getMessage(), e);
        }
    }

    private byte[] receiveExpectedFrame(String what)
            throws SessionException, TransportException, InterruptedException {
        byte[] bytes = transport.receive(handshakeTimeoutMs, TimeUnit.MILLISECONDS);
        if (bytes == null) {
            throw new SessionException(
                    what + " not received within " + handshakeTimeoutMs + "ms");
        }
        lastRecvNanos.set(System.nanoTime());
        return bytes;
    }

    private void startBackgroundTasks() {
        ThreadFactory tf = r -> {
            Thread t = new Thread(r, "zenoh-session-" + Integer.toHexString(System.identityHashCode(this)));
            t.setDaemon(true);
            return t;
        };
        // Reader thread pulls inbound frames and dispatches session-level messages.
        Thread rt = new Thread(this::readerLoop,
                "zenoh-session-reader-" + Integer.toHexString(System.identityHashCode(this)));
        rt.setDaemon(true);
        this.readerThread = rt;
        rt.start();

        // Scheduler for KEEP_ALIVE + lease watchdog.
        ScheduledExecutorService s = Executors.newSingleThreadScheduledExecutor(tf);
        this.scheduler = s;
        long tickMs = Math.max(1, negotiatedLeaseMs / 4);
        this.keepAliveTask = s.scheduleAtFixedRate(
                this::maybeSendKeepAlive, tickMs, tickMs, TimeUnit.MILLISECONDS);
        // Watchdog also ticks at lease/4 for prompt detection.
        this.leaseWatchdogTask = s.scheduleAtFixedRate(
                this::checkLeaseExpiry, tickMs, tickMs, TimeUnit.MILLISECONDS);
    }

    private void cancelBackgroundTasks() {
        ScheduledFuture<?> k = keepAliveTask;
        if (k != null) k.cancel(false);
        ScheduledFuture<?> w = leaseWatchdogTask;
        if (w != null) w.cancel(false);
        ScheduledExecutorService s = scheduler;
        if (s != null) {
            s.shutdownNow();
            try { s.awaitTermination(closeTimeoutMs, TimeUnit.MILLISECONDS); }
            catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
        }
        Thread rt = readerThread;
        if (rt != null && rt != Thread.currentThread()) {
            try { rt.join(closeTimeoutMs); }
            catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
        }
    }

    private void maybeSendKeepAlive() {
        if (state.get() != SessionState.OPEN) return;
        long silenceNs = System.nanoTime() - lastSendNanos.get();
        long thresholdNs = TimeUnit.MILLISECONDS.toNanos(Math.max(1, negotiatedLeaseMs / 4));
        if (silenceNs < thresholdNs) return;
        try {
            // KEEP_ALIVE goes on the wire as itself, NOT wrapped in FRAME.
            // In Zenoh 1.x transport, KEEP_ALIVE is a standalone transport message
            // sharing the same length-prefixed batching envelope as any other.
            sendRaw(KeepAlive.EMPTY.encode());
        } catch (SessionException e) {
            LOG.log(Level.DEBUG, () -> "KEEP_ALIVE send failed: " + e.getMessage());
            close();
        }
    }

    private void checkLeaseExpiry() {
        if (state.get() != SessionState.OPEN) return;
        long silenceNs = System.nanoTime() - lastRecvNanos.get();
        long leaseNs = TimeUnit.MILLISECONDS.toNanos(negotiatedLeaseMs);
        if (silenceNs > leaseNs) {
            LOG.log(Level.DEBUG, () ->
                    "lease expired: no inbound traffic in " + (silenceNs / 1_000_000) + "ms > "
                            + negotiatedLeaseMs + "ms");
            close();
        }
    }

    private void readerLoop() {
        try {
            while (state.get() == SessionState.OPEN) {
                byte[] batch;
                try {
                    batch = transport.receive(100, TimeUnit.MILLISECONDS);
                } catch (TransportException te) {
                    LOG.log(Level.DEBUG, () -> "reader: transport error: " + te.getMessage());
                    close();
                    return;
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (batch == null) {
                    if (!transport.isOpen()) { close(); return; }
                    continue;
                }
                lastRecvNanos.set(System.nanoTime());
                dispatch(batch);
            }
        } catch (RuntimeException re) {
            LOG.log(Level.DEBUG, () -> "reader crashed: " + re);
            close();
        }
    }

    private void dispatch(byte[] batch) {
        if (batch.length == 0) return;
        int id = batch[0] & 0x1F;
        switch (id) {
            case KeepAlive.ID -> {
                // Server keep-alive; nothing to do beyond bumping lastRecv (already done).
            }
            case Close.ID -> {
                LOG.log(Level.DEBUG, () -> "peer sent CLOSE");
                close();
            }
            case Frame.ID -> {
                // Server-to-client frames aren't part of the publish path; log and drop.
                LOG.log(Level.TRACE, () -> "reader: server FRAME (ignored by publisher)");
            }
            default -> LOG.log(Level.DEBUG, () ->
                    "reader: unknown message id 0x" + Integer.toHexString(id));
        }
    }

    private void forceClosed() {
        cancelBackgroundTasks();
        transport.close();
        state.set(SessionState.CLOSED);
    }

    // ---- Builder ---------------------------------------------------------

    public static final class Builder {
        private final Transport transport;
        private       ZenohId   localId;
        private       long      leaseMs             = DEFAULT_LEASE_MS;
        private       int       handshakeTimeoutMs  = DEFAULT_HANDSHAKE_MS;
        private       int       closeTimeoutMs      = DEFAULT_CLOSE_MS;
        private       boolean   autoConnect         = true;

        private Builder(Transport transport) {
            this.transport = Objects.requireNonNull(transport, "transport");
        }

        /** Explicit local ZenohId. Default is a fresh random 16-byte id. */
        public Builder localId(ZenohId id) {
            this.localId = Objects.requireNonNull(id, "id");
            return this;
        }

        /** Lease we propose to the router (ms). Default {@value #DEFAULT_LEASE_MS}. */
        public Builder leaseMs(long ms) {
            if (ms < 1_000) throw new IllegalArgumentException("lease must be >= 1000 ms");
            this.leaseMs = ms;
            return this;
        }

        /** Per-step handshake receive timeout (ms). Default {@value #DEFAULT_HANDSHAKE_MS}. */
        public Builder handshakeTimeoutMs(int ms) {
            if (ms < 1) throw new IllegalArgumentException("ms must be >= 1");
            this.handshakeTimeoutMs = ms;
            return this;
        }

        /** Deadline for CLOSE-frame flush + scheduler shutdown (ms). Default {@value #DEFAULT_CLOSE_MS}. */
        public Builder closeTimeoutMs(int ms) {
            if (ms < 0) throw new IllegalArgumentException("ms must be >= 0");
            this.closeTimeoutMs = ms;
            return this;
        }

        /** When true (default), {@link #open()} calls {@link Transport#connect()} first if needed. */
        public Builder autoConnect(boolean on) {
            this.autoConnect = on;
            return this;
        }

        public ZenohSession build() {
            return new ZenohSession(this);
        }
    }
}
