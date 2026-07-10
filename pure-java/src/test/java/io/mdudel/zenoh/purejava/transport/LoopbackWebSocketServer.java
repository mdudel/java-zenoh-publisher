/*
 * Copyright 2026 the java-zenoh-publisher-pure contributors.
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 */
package io.mdudel.zenoh.purejava.transport;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLServerSocketFactory;

/**
 * Minimal RFC 6455 WebSocket server for tests. Not fit for production
 * &mdash; supports exactly what we need to exercise {@link WsTransport}:
 * client-driven binary frames, server-driven binary frames, ping/pong,
 * fragmented messages, and clean-close.
 *
 * <p>Only used by {@code src/test/} \u2014 keeps
 * {@code java-zenoh-publisher-pure} at zero runtime dependencies.</p>
 */
final class LoopbackWebSocketServer implements AutoCloseable {

    private static final String RFC6455_MAGIC = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    private final ServerSocket server;
    private final Thread       acceptThread;
    private final CopyOnWriteArrayList<Session> sessions = new CopyOnWriteArrayList<>();
    private final AtomicBoolean closed = new AtomicBoolean(false);

    // Optional hook invoked once per accepted connection AFTER the WebSocket
    // handshake completes; the test uses this to script server-side behaviour.
    private volatile BiConsumer<Session, LoopbackWebSocketServer> onSession =
            (s, srv) -> { /* default: passive echo of every binary frame */ };

    static LoopbackWebSocketServer bindPlain() throws IOException {
        ServerSocket s = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
        return new LoopbackWebSocketServer(s);
    }

    static LoopbackWebSocketServer bindTls(SSLContext ctx) throws IOException {
        SSLServerSocketFactory f = ctx.getServerSocketFactory();
        SSLServerSocket ss = (SSLServerSocket) f.createServerSocket(
                0, 1, InetAddress.getByName("127.0.0.1"));
        return new LoopbackWebSocketServer(ss);
    }

    private LoopbackWebSocketServer(ServerSocket server) {
        this.server = server;
        this.acceptThread = new Thread(this::acceptLoop, "loopback-ws-accept");
        this.acceptThread.setDaemon(true);
        this.acceptThread.start();
    }

    int port() { return server.getLocalPort(); }

    /** Install a per-session behaviour hook (default is passive echo of binary frames). */
    void onSession(BiConsumer<Session, LoopbackWebSocketServer> hook) {
        this.onSession = hook;
    }

    List<Session> sessions() { return List.copyOf(sessions); }

    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        try { server.close(); } catch (IOException ignored) {}
        for (Session s : sessions) s.close();
    }

    private void acceptLoop() {
        try {
            while (!closed.get()) {
                Socket sock;
                try { sock = server.accept(); }
                catch (IOException e) { if (closed.get()) return; throw new RuntimeException(e); }
                Session s = new Session(sock);
                sessions.add(s);
                new Thread(() -> s.run(onSession), "loopback-ws-session-" + s.hashCode())
                        .start();
            }
        } catch (RuntimeException ignored) {}
    }

    /** One accepted WebSocket connection. */
    static final class Session implements AutoCloseable {

        private final Socket       socket;
        private final AtomicBoolean sessionClosed = new AtomicBoolean(false);

        /** Frames the server has received from the client (binary payloads). */
        final LinkedBlockingQueue<byte[]> inbound = new LinkedBlockingQueue<>();

        private volatile OutputStream out;

        Session(Socket socket) { this.socket = socket; }

        void run(BiConsumer<Session, LoopbackWebSocketServer> hook) {
            try {
                DataInputStream in = new DataInputStream(socket.getInputStream());
                OutputStream    os = socket.getOutputStream();
                this.out = os;
                Map<String, String> headers = readHttpHeaders(in);
                String key = headers.get("sec-websocket-key");
                if (key == null) throw new IOException("no Sec-WebSocket-Key");
                writeHandshakeResponse(os, key);

                // Hand off to the test-configured behaviour hook.
                Thread t = new Thread(() -> hook.accept(this, null), "loopback-ws-hook");
                t.setDaemon(true);
                t.start();

                // Read loop: parse binary frames, drop payload into inbound queue.
                while (!sessionClosed.get()) {
                    Frame f = readFrame(in);
                    if (f == null) return;
                    switch (f.opcode) {
                        case 0x1 -> { /* text — Zenoh never sends this; drop */ }
                        case 0x2 -> inbound.offer(f.payload);
                        case 0x8 -> {  // close
                            sendClose(1000);
                            return;
                        }
                        case 0x9 -> sendFrame(0xA, f.payload);  // ping → pong
                        case 0xA -> { /* pong — ignore */ }
                        default  -> throw new IOException("unexpected opcode 0x" + Integer.toHexString(f.opcode));
                    }
                }
            } catch (IOException ignored) {
            } finally {
                close();
            }
        }

        /** Send a binary frame to the client. */
        void sendBinary(byte[] payload) throws IOException {
            sendFrame(0x2, payload);
        }

        /** Send a binary frame fragmented into pieces of {@code chunkSize} bytes. */
        void sendBinaryFragmented(byte[] payload, int chunkSize) throws IOException {
            if (payload.length == 0) { sendFrame(0x2, payload); return; }
            for (int off = 0, n = 0; off < payload.length; off += chunkSize, n++) {
                int len = Math.min(chunkSize, payload.length - off);
                byte[] chunk = new byte[len];
                System.arraycopy(payload, off, chunk, 0, len);
                boolean last = (off + len == payload.length);
                int opcode = (n == 0) ? 0x2 : 0x0;  // first=binary, rest=continuation
                sendFrame(opcode, chunk, last, /* mask = */ false);
            }
        }

        void sendText(String s) throws IOException {
            sendFrame(0x1, s.getBytes(StandardCharsets.UTF_8));
        }

        void sendClose(int code) throws IOException {
            sendFrame(0x8, new byte[] { (byte) ((code >>> 8) & 0xFF), (byte) (code & 0xFF) });
            sessionClosed.set(true);
        }

        private void sendFrame(int opcode, byte[] payload) throws IOException {
            sendFrame(opcode, payload, true, false);
        }

        private void sendFrame(int opcode, byte[] payload, boolean fin, boolean mask) throws IOException {
            OutputStream os = out;
            synchronized (os) {
                int b0 = (fin ? 0x80 : 0x00) | (opcode & 0x0F);
                os.write(b0);
                int len = payload.length;
                if (len < 126) {
                    os.write((mask ? 0x80 : 0x00) | len);
                } else if (len < 0x10000) {
                    os.write((mask ? 0x80 : 0x00) | 126);
                    os.write((len >>> 8) & 0xFF);
                    os.write(len & 0xFF);
                } else {
                    os.write((mask ? 0x80 : 0x00) | 127);
                    for (int i = 7; i >= 0; i--) os.write((int) ((((long) len) >>> (i * 8)) & 0xFF));
                }
                os.write(payload);
                os.flush();
            }
        }

        @Override public void close() {
            if (!sessionClosed.compareAndSet(false, true)) return;
            try { socket.close(); } catch (IOException ignored) {}
        }

        private static Map<String, String> readHttpHeaders(InputStream in) throws IOException {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            int prev1 = -1, prev2 = -1, prev3 = -1, prev4 = -1;
            while (true) {
                int b = in.read();
                if (b < 0) throw new EOFException("EOF in HTTP headers");
                buf.write(b);
                if (prev3 == '\r' && prev2 == '\n' && prev1 == '\r' && b == '\n') break;
                prev4 = prev3; prev3 = prev2; prev2 = prev1; prev1 = b;
            }
            String[] lines = buf.toString(StandardCharsets.ISO_8859_1).split("\r\n");
            Map<String, String> out = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            for (int i = 1; i < lines.length; i++) {
                int idx = lines[i].indexOf(':');
                if (idx < 0) continue;
                out.put(lines[i].substring(0, idx).trim().toLowerCase(Locale.ROOT),
                        lines[i].substring(idx + 1).trim());
            }
            return out;
        }

        private static void writeHandshakeResponse(OutputStream os, String key) throws IOException {
            String accept = accept(key);
            String resp = "HTTP/1.1 101 Switching Protocols\r\n"
                    + "Upgrade: websocket\r\n"
                    + "Connection: Upgrade\r\n"
                    + "Sec-WebSocket-Accept: " + accept + "\r\n\r\n";
            os.write(resp.getBytes(StandardCharsets.ISO_8859_1));
            os.flush();
        }

        private static String accept(String key) {
            try {
                MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
                byte[] hash = sha1.digest((key + RFC6455_MAGIC).getBytes(StandardCharsets.ISO_8859_1));
                return Base64.getEncoder().encodeToString(hash);
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException("SHA-1 unavailable", e);
            }
        }

        private static Frame readFrame(DataInputStream in) throws IOException {
            int b0, b1;
            try { b0 = in.readUnsignedByte(); b1 = in.readUnsignedByte(); }
            catch (EOFException eof) { return null; }
            int opcode = b0 & 0x0F;
            boolean masked = (b1 & 0x80) != 0;
            long len = b1 & 0x7F;
            if (len == 126) len = in.readUnsignedShort();
            else if (len == 127) len = in.readLong();
            byte[] mask = new byte[0];
            if (masked) { mask = new byte[4]; in.readFully(mask); }
            if (len > StreamFramer.MAX_FRAME_BYTES) {
                throw new IOException("client frame too big: " + len);
            }
            byte[] payload = new byte[(int) len];
            in.readFully(payload);
            if (masked) {
                for (int i = 0; i < payload.length; i++) payload[i] ^= mask[i & 3];
            }
            return new Frame(opcode, payload);
        }

        private record Frame(int opcode, byte[] payload) {}
    }
}
