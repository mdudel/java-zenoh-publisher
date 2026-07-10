/*
 * Copyright 2026 the java-zenoh-publisher-pure contributors.
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * Clean-room pure-Java implementation of the Eclipse Zenoh 1.x wire protocol.
 */
package io.mdudel.zenoh.purejava.transport;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.util.Objects;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Plain-TCP implementation of {@link Transport} for the Zenoh 1.x
 * publisher.
 *
 * <p>One TCP socket per instance. On {@link #connect()}, opens the
 * socket, wraps I/O streams with modest buffering, and spins up a
 * single reader thread that runs {@link StreamFramer#readFrame(InputStream)}
 * in a loop and drops each received batch into an unbounded
 * {@link LinkedBlockingQueue}. The queue is unbounded because Zenoh's
 * batch cap is 64 KB &mdash; back-pressure from a slow consumer is a
 * caller-side concern, not the transport's, and the accreditation-friendly
 * shape here is to fail fast on {@code OutOfMemoryError} rather than
 * silently drop batches in the transport layer.</p>
 *
 * <p>Threading:</p>
 * <ul>
 *   <li>{@link #send(byte[])} synchronises on a per-instance monitor so
 *       concurrent publishers serialise cleanly on the same socket.</li>
 *   <li>The reader thread is a daemon so it never blocks JVM shutdown.</li>
 *   <li>{@link #close()} shuts the socket down input-first to unblock
 *       the reader, then joins it with a short timeout.</li>
 * </ul>
 *
 * <p>No TCP-level keepalive by default (Zenoh handles this at the
 * session layer via {@code KeepAlive} messages, added in Turn D). No
 * Nagle disable by default either &mdash; a future
 * {@link #setTcpNoDelay(boolean)} option can toggle that if latency
 * measurements ever justify it.</p>
 */
public final class TcpTransport implements Transport {

    private static final Logger LOG = System.getLogger(TcpTransport.class.getName());

    /** Default socket connect timeout (ms). Configurable via builder-ish setter. */
    public static final int DEFAULT_CONNECT_TIMEOUT_MS = 10_000;

    /** Default reader-thread stop-join timeout on close (ms). */
    private static final int READER_JOIN_TIMEOUT_MS = 2_000;

    private final String  host;
    private final int     port;
    private       int     connectTimeoutMs = DEFAULT_CONNECT_TIMEOUT_MS;
    private       boolean tcpNoDelay       = true;

    // --- lifecycle state, all volatile / atomic so peekers stay honest ---
    private final AtomicBoolean opened = new AtomicBoolean(false);
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final Object        writeLock = new Object();

    private volatile Socket       socket;
    private volatile OutputStream out;
    private volatile InputStream  in;
    private volatile Thread       readerThread;

    private final LinkedBlockingQueue<byte[]>   inbox    = new LinkedBlockingQueue<>();
    private final AtomicReference<Throwable>    readerError = new AtomicReference<>();

    public TcpTransport(String host, int port) {
        this.host = Objects.requireNonNull(host, "host");
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("port out of range: " + port);
        }
        this.port = port;
    }

    /** Set the socket-level connect timeout in ms. Only takes effect if called before {@link #connect()}. */
    public TcpTransport setConnectTimeoutMs(int ms) {
        if (opened.get()) {
            throw new IllegalStateException("connectTimeoutMs must be set before connect()");
        }
        if (ms < 0) throw new IllegalArgumentException("ms must be >= 0");
        this.connectTimeoutMs = ms;
        return this;
    }

    /** Enable / disable Nagle. Default: {@code true} (Nagle disabled), because
     *  Zenoh already batches into frames and buffering at the OS is redundant. */
    public TcpTransport setTcpNoDelay(boolean on) {
        if (opened.get()) {
            throw new IllegalStateException("tcpNoDelay must be set before connect()");
        }
        this.tcpNoDelay = on;
        return this;
    }

    @Override
    public void connect() throws TransportException {
        if (!opened.compareAndSet(false, true)) {
            throw new IllegalStateException("connect() may only be called once per instance");
        }
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(host, port), connectTimeoutMs);
            s.setTcpNoDelay(tcpNoDelay);
            // Modest buffering to smooth 2-byte prefix + payload writes into a single syscall.
            this.out = new BufferedOutputStream(s.getOutputStream());
            this.in  = new BufferedInputStream(s.getInputStream());
            this.socket = s;
        } catch (IOException e) {
            safeClose(s);
            opened.set(false);
            throw new TransportException(
                    "TCP connect failed to " + describe() + ": " + e.getMessage(), e);
        }
        Thread t = new Thread(this::readerLoop, "zenoh-tcp-reader-" + host + ":" + port);
        t.setDaemon(true);
        this.readerThread = t;
        t.start();
        LOG.log(Level.DEBUG, () -> "connected " + describe());
    }

    @Override
    public void send(byte[] batch) throws TransportException {
        if (batch == null) {
            throw new IllegalArgumentException("batch must not be null");
        }
        if (!isOpen()) {
            throw new TransportException("send on closed transport " + describe());
        }
        if (batch.length > StreamFramer.MAX_FRAME_BYTES) {
            // Same wire cap as StreamFramer. Surface loudly here so callers
            // don't see the more cryptic writeFrame IllegalArgumentException.
            throw new TransportException(
                    "batch exceeds " + StreamFramer.MAX_FRAME_BYTES
                            + "-byte wire cap: " + batch.length);
        }
        synchronized (writeLock) {
            // Re-check under lock: close() might have raced.
            if (!isOpen()) {
                throw new TransportException("send on closed transport " + describe());
            }
            try {
                StreamFramer.writeFrame(out, batch);
                out.flush();
            } catch (IOException e) {
                // Send failure is always fatal for the link.
                closeInternal("send failure", e);
                throw new TransportException("send failed on " + describe(), e);
            }
        }
    }

    @Override
    public byte[] receive(long timeout, TimeUnit unit)
            throws TransportException, InterruptedException {
        byte[] batch = inbox.poll(timeout, unit);
        if (batch != null) return batch;
        // Nothing in the queue. If the reader died with an error, surface it now.
        Throwable err = readerError.get();
        if (err != null) {
            if (err instanceof EOFException) {
                // Clean remote close: return null and let caller notice via isOpen().
                return null;
            }
            throw new TransportException(
                    "reader ended with error on " + describe() + ": " + err.getMessage(), err);
        }
        // Either the reader is still healthy (real timeout) or shut down cleanly.
        return null;
    }

    @Override public boolean isOpen() {
        return opened.get() && !closed.get();
    }

    @Override public String describe() {
        return "tcp/" + host + ":" + port;
    }

    @Override public void close() {
        closeInternal("caller close", null);
    }

    // ---- internals ----------------------------------------------------

    private void readerLoop() {
        try {
            while (!closed.get()) {
                byte[] batch;
                try {
                    batch = StreamFramer.readFrame(in);
                } catch (EOFException eof) {
                    // Remote closed cleanly at a frame boundary (or partial). Treat both as end-of-stream.
                    readerError.compareAndSet(null, eof);
                    return;
                } catch (SocketException se) {
                    // Local close() shuts down input which surfaces here; treat as clean.
                    if (closed.get()) return;
                    readerError.compareAndSet(null, se);
                    return;
                } catch (IOException ioe) {
                    readerError.compareAndSet(null, ioe);
                    return;
                }
                inbox.offer(batch);
            }
        } finally {
            // Any exit from the loop implies the link is down. Trigger close for downstream cleanup.
            closeInternal("reader exit", null);
        }
    }

    private void closeInternal(String reason, Throwable cause) {
        if (!closed.compareAndSet(false, true)) return;
        if (cause != null) {
            LOG.log(Level.DEBUG, () -> "closing " + describe() + " (" + reason + "): " + cause);
        } else {
            LOG.log(Level.DEBUG, () -> "closing " + describe() + " (" + reason + ")");
        }
        Socket s = socket;
        if (s != null) {
            // Shut input first so the reader unblocks with EOF or SocketException.
            try { s.shutdownInput();  } catch (IOException ignored) {}
            try { s.shutdownOutput(); } catch (IOException ignored) {}
            safeClose(s);
        }
        Thread t = readerThread;
        if (t != null && t != Thread.currentThread()) {
            try {
                t.join(READER_JOIN_TIMEOUT_MS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static void safeClose(Socket s) {
        try { s.close(); } catch (IOException ignored) {}
    }
}
