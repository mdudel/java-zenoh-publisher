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
import java.net.Socket;
import java.net.SocketException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Shared skeleton for all stream-oriented {@link Transport} implementations
 * (TCP, TLS, WSS). Subclasses supply the socket via {@link #openSocket()};
 * this class owns the reader thread, the write lock, the receive inbox, and
 * the idempotent-close protocol.
 *
 * <p>Threading contract inherited by every subclass:</p>
 * <ul>
 *   <li>{@link #send(byte[])} synchronises on a per-instance monitor so
 *       concurrent publishers serialise cleanly on the same socket.</li>
 *   <li>The reader thread is a daemon; it never blocks JVM shutdown.</li>
 *   <li>{@link #close()} shuts input first so the reader unblocks with
 *       {@link EOFException} or {@link SocketException}, then joins with
 *       a short timeout. Input-first is deliberate &mdash; closing the
 *       socket without shutting input first can leave the reader blocked
 *       on some Linux + JDK combinations.</li>
 * </ul>
 *
 * <p>Framing is delegated to {@link StreamFramer}, which enforces the
 * 2-byte little-endian length prefix and the {@link StreamFramer#MAX_FRAME_BYTES}
 * hard wire cap.</p>
 */
public abstract class AbstractStreamTransport implements Transport {

    private static final Logger LOG = System.getLogger(AbstractStreamTransport.class.getName());

    /** Reader-thread stop-join timeout on close (ms). */
    private static final int READER_JOIN_TIMEOUT_MS = 2_000;

    // ---- lifecycle state, all volatile / atomic so peekers stay honest ----
    private final AtomicBoolean opened = new AtomicBoolean(false);
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final Object        writeLock = new Object();

    private volatile Socket       socket;
    private volatile OutputStream out;
    private volatile InputStream  in;
    private volatile Thread       readerThread;

    private final LinkedBlockingQueue<byte[]> inbox       = new LinkedBlockingQueue<>();
    private final AtomicReference<Throwable>  readerError = new AtomicReference<>();

    /**
     * Open the underlying stream socket. Called exactly once from
     * {@link #connect()}. Implementations are responsible for connect
     * timeout, TCP tunables, TLS handshake, and any other transport-specific
     * setup. Must return a fully connected socket.
     *
     * @throws IOException on any I/O failure; the subclass should also
     *                     close any partially-opened socket before rethrowing
     */
    protected abstract Socket openSocket() throws IOException;

    /** Reader-thread name suffix; typically the endpoint (e.g. {@code "127.0.0.1:7447"}). */
    protected abstract String readerThreadTag();

    @Override
    public final void connect() throws TransportException {
        if (!opened.compareAndSet(false, true)) {
            throw new IllegalStateException("connect() may only be called once per instance");
        }
        Socket s;
        try {
            s = openSocket();
            // Modest buffering to smooth 2-byte prefix + payload writes into a single syscall.
            this.out    = new BufferedOutputStream(s.getOutputStream());
            this.in     = new BufferedInputStream(s.getInputStream());
            this.socket = s;
        } catch (IOException e) {
            opened.set(false);
            throw new TransportException(
                    "connect failed to " + describe() + ": " + e.getMessage(), e);
        }
        Thread t = new Thread(this::readerLoop, "zenoh-" + readerThreadTag() + "-reader");
        t.setDaemon(true);
        this.readerThread = t;
        t.start();
        LOG.log(Level.DEBUG, () -> "connected " + describe());
    }

    @Override
    public final void send(byte[] batch) throws TransportException {
        if (batch == null) {
            throw new IllegalArgumentException("batch must not be null");
        }
        if (!isOpen()) {
            throw new TransportException("send on closed transport " + describe());
        }
        if (batch.length > StreamFramer.MAX_FRAME_BYTES) {
            // Same wire cap as StreamFramer. Surface loudly here so callers don't
            // see the more cryptic writeFrame IllegalArgumentException.
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
    public final byte[] receive(long timeout, TimeUnit unit)
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

    @Override public final boolean isOpen() {
        return opened.get() && !closed.get();
    }

    @Override public final void close() {
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
                    // Remote closed at a frame boundary (clean or partial). End-of-stream.
                    readerError.compareAndSet(null, eof);
                    return;
                } catch (SocketException se) {
                    // Local close() shuts input which surfaces here; treat as clean.
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
