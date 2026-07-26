package com.xai.sudokupro.client.net;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A minimal RFC 6455 server, just enough to complete a real handshake with
 * {@code java.net.http.WebSocket} and then close the connection however the test
 * wants.
 *
 * <p>It exists because {@code GameSocket.open} — the handshake, the mapping of a
 * rejected upgrade onto an HTTP status, and the delivery of a real close code —
 * is the one part of the channel that a fake {@link GameLink} cannot exercise, and
 * it is precisely the part the reconnect policy depends on. The client module has
 * no servlet container to borrow, and the JDK ships a WebSocket <em>client</em>
 * only, so the sixty lines below are cheaper than the alternative of leaving the
 * transport untested.
 */
final class RawWebSocketServer implements AutoCloseable {

    private static final String MAGIC = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    private final ServerSocket serverSocket;
    private final Thread acceptor;
    private final List<Socket> accepted = new CopyOnWriteArrayList<>();
    private final List<String> authHeaders = new CopyOnWriteArrayList<>();
    private final List<String> requestLines = new CopyOnWriteArrayList<>();
    private final AtomicInteger handshakes = new AtomicInteger();
    private final CountDownLatch firstHandshake = new CountDownLatch(1);

    /** When >= 400, the upgrade is refused with this HTTP status instead of accepted. */
    volatile int refuseWithStatus = 0;
    /** When non-zero, the connection is closed with this WebSocket status right after the handshake. */
    volatile int closeImmediatelyWith = 0;
    volatile String closeReason = "";

    RawWebSocketServer() throws IOException {
        serverSocket = new ServerSocket(0, 0, java.net.InetAddress.getLoopbackAddress());
        acceptor = new Thread(this::acceptLoop, "fake-ws-acceptor");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    String uriBase() {
        return "ws://localhost:" + serverSocket.getLocalPort();
    }

    String httpBase() {
        return "http://localhost:" + serverSocket.getLocalPort();
    }

    int handshakeCount() {
        return handshakes.get();
    }

    List<String> seenAuthHeaders() {
        return new ArrayList<>(authHeaders);
    }

    List<String> seenRequestLines() {
        return new ArrayList<>(requestLines);
    }

    boolean awaitFirstHandshake(long millis) throws InterruptedException {
        return firstHandshake.await(millis, TimeUnit.MILLISECONDS);
    }

    /** Drops every live connection at the TCP level, as a network failure would. */
    void dropAllConnections() {
        for (Socket socket : accepted) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // already gone
            }
        }
        accepted.clear();
    }

    private void acceptLoop() {
        while (!serverSocket.isClosed()) {
            try {
                Socket socket = serverSocket.accept();
                accepted.add(socket);
                handleOne(socket);
            } catch (IOException e) {
                return;   // server closed
            }
        }
    }

    private void handleOne(Socket socket) throws IOException {
        InputStream in = socket.getInputStream();
        OutputStream out = socket.getOutputStream();
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));

        String requestLine = reader.readLine();
        if (requestLine == null) return;
        requestLines.add(requestLine);

        String key = null;
        String line;
        while ((line = reader.readLine()) != null && !line.isEmpty()) {
            int colon = line.indexOf(':');
            if (colon < 0) continue;
            String name = line.substring(0, colon).trim();
            String value = line.substring(colon + 1).trim();
            if ("Sec-WebSocket-Key".equalsIgnoreCase(name)) key = value;
            if ("Authorization".equalsIgnoreCase(name)) authHeaders.add(value);
        }

        int refuse = refuseWithStatus;
        if (refuse >= 400) {
            out.write(("HTTP/1.1 " + refuse + " Refused\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
                .getBytes(StandardCharsets.UTF_8));
            out.flush();
            socket.close();
            return;
        }

        out.write(("HTTP/1.1 101 Switching Protocols\r\n"
            + "Upgrade: websocket\r\n"
            + "Connection: Upgrade\r\n"
            + "Sec-WebSocket-Accept: " + accept(key) + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
        handshakes.incrementAndGet();
        firstHandshake.countDown();

        int closeWith = closeImmediatelyWith;
        if (closeWith != 0) {
            out.write(closeFrame(closeWith, closeReason));
            out.flush();
        }
    }

    private static String accept(String key) {
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            return Base64.getEncoder().encodeToString(
                sha1.digest((key + MAGIC).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** An unmasked server close frame: FIN + opcode 0x8, 2-byte status, then the reason. */
    private static byte[] closeFrame(int status, String reason) {
        byte[] reasonBytes = reason == null ? new byte[0] : reason.getBytes(StandardCharsets.UTF_8);
        byte[] payload = new byte[2 + reasonBytes.length];
        payload[0] = (byte) ((status >> 8) & 0xFF);
        payload[1] = (byte) (status & 0xFF);
        System.arraycopy(reasonBytes, 0, payload, 2, reasonBytes.length);

        byte[] frame = new byte[2 + payload.length];
        frame[0] = (byte) 0x88;                 // FIN + close
        frame[1] = (byte) payload.length;       // server frames are unmasked, length < 126
        System.arraycopy(payload, 0, frame, 2, payload.length);
        return frame;
    }

    @Override
    public void close() {
        dropAllConnections();
        try {
            serverSocket.close();
        } catch (IOException ignored) {
            // nothing useful to do
        }
        acceptor.interrupt();
    }
}
