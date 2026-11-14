package com.pacenote.wrcgdash;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.net.ssl.SSLSocketFactory;

final class TelemetryClient {
    interface Listener {
        void onConnectionState(int state, String detail);
        void onTelemetry(float speedKph, float rpm, float maxRpm, String gear);
        void onLatency(long roundTripMs);
    }

    static final int STATE_CONNECTING = 0;
    static final int STATE_CONNECTED = 1;
    static final int STATE_DISCONNECTED = 2;

    private static final String WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    private static final int MAX_FRAME_BYTES = 1024 * 1024;

    private final Listener listener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final SecureRandom random = new SecureRandom();
    private final Object sendLock = new Object();
    private final ExecutorService sendExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "WRCG-WebSocket-Send");
        thread.setDaemon(true);
        return thread;
    });

    private volatile boolean running;
    private volatile Socket socket;
    private volatile OutputStream output;
    private Thread worker;

    TelemetryClient(android.content.Context ignored, String serverUrl, Listener listener) {
        this.endpoint = serverUrl;
        this.listener = listener;
    }

    private final String endpoint;

    void start() {
        if (running) return;
        running = true;
        worker = new Thread(this::connectionLoop, "WRCG-WebSocket");
        worker.setDaemon(true);
        worker.start();
    }

    void shutdown() {
        running = false;
        closeSocket();
        sendExecutor.shutdownNow();
        Thread thread = worker;
        if (thread != null) thread.interrupt();
        worker = null;
    }

    void ping() {
        if (!running || output == null) return;
        try {
            sendExecutor.execute(() -> {
                try {
                    sendText("{\"type\":\"ping\",\"sentAt\":" + System.currentTimeMillis() + "}");
                } catch (IOException ignored) {
                    // A reconnect cycle will recover the connection.
                }
            });
        } catch (RuntimeException ignored) {
            // Client was shut down between the running check and task submission.
        }
    }

    private void connectionLoop() {
        while (running) {
            notifyState(STATE_CONNECTING, "连接 " + endpoint);
            try {
                URI uri = URI.create(endpoint);
                String host = uri.getHost();
                String scheme = uri.getScheme();
                int port = uri.getPort() > 0 ? uri.getPort() : 8787;
                String path = uri.getRawPath();
                if (path == null || path.isEmpty()) path = "/ws";

                Socket connectedSocket = "wss".equalsIgnoreCase(scheme)
                    ? SSLSocketFactory.getDefault().createSocket()
                    : new Socket();
                connectedSocket.connect(new InetSocketAddress(host, port), 4000);
                connectedSocket.setTcpNoDelay(true);
                connectedSocket.setKeepAlive(true);
                connectedSocket.setSoTimeout(25000);
                socket = connectedSocket;

                InputStream rawInput = new BufferedInputStream(connectedSocket.getInputStream(), 8192);
                OutputStream rawOutput = new BufferedOutputStream(connectedSocket.getOutputStream(), 8192);
                output = rawOutput;
                handshake(rawInput, rawOutput, host, port, path);
                sendText("{\"type\":\"hello\"}");
                notifyState(STATE_CONNECTED, "实时");

                while (running) {
                    readFrame(rawInput);
                }
            } catch (Exception error) {
                if (running) notifyState(STATE_DISCONNECTED, shortMessage(error));
            } finally {
                closeSocket();
            }

            if (running) {
                try {
                    Thread.sleep(1200L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    private void handshake(InputStream input, OutputStream stream, String host, int port, String path)
        throws IOException {
        byte[] nonce = new byte[16];
        random.nextBytes(nonce);
        String key = Base64.getEncoder().encodeToString(nonce);
        String request = "GET " + path + " HTTP/1.1\r\n"
            + "Host: " + host + ":" + port + "\r\n"
            + "Upgrade: websocket\r\n"
            + "Connection: Upgrade\r\n"
            + "Sec-WebSocket-Key: " + key + "\r\n"
            + "Sec-WebSocket-Version: 13\r\n"
            + "\r\n";
        stream.write(request.getBytes(StandardCharsets.US_ASCII));
        stream.flush();

        StringBuilder headers = new StringBuilder();
        int matched = 0;
        while (headers.length() < 8192) {
            int value = input.read();
            if (value < 0) throw new EOFException("WebSocket handshake closed");
            headers.append((char) value);
            if ((matched == 0 && value == '\r') || (matched == 1 && value == '\n')
                || (matched == 2 && value == '\r') || (matched == 3 && value == '\n')) {
                matched += 1;
            } else {
                matched = value == '\r' ? 1 : 0;
            }
            if (matched == 4) break;
        }
        String response = headers.toString();
        if (!response.startsWith("HTTP/1.1 101") && !response.startsWith("HTTP/1.0 101")) {
            throw new IOException("WebSocket upgrade rejected");
        }
    }

    private void readFrame(InputStream input) throws IOException {
        int first = input.read();
        if (first < 0) throw new EOFException("WebSocket closed");
        int second = input.read();
        if (second < 0) throw new EOFException("WebSocket closed");

        boolean masked = (second & 0x80) != 0;
        long length = second & 0x7f;
        if (length == 126) {
            length = ((long) input.read() << 8) | input.read();
        } else if (length == 127) {
            length = 0;
            for (int index = 0; index < 8; index += 1) {
                int next = input.read();
                if (next < 0) throw new EOFException("WebSocket closed");
                length = (length << 8) | next;
            }
        }
        if (length < 0 || length > MAX_FRAME_BYTES) throw new IOException("WebSocket frame too large");

        byte[] mask = null;
        if (masked) {
            mask = new byte[4];
            readFully(input, mask, 0, 4);
        }
        byte[] payload = new byte[(int) length];
        readFully(input, payload, 0, payload.length);
        if (mask != null) {
            for (int index = 0; index < payload.length; index += 1) {
                payload[index] ^= mask[index % 4];
            }
        }

        int opcode = first & 0x0f;
        if (opcode == 0x8) throw new EOFException("WebSocket closed");
        if (opcode == 0x9) {
            sendFrame(0xA, payload);
            return;
        }
        if (opcode == 0x1) {
            parseMessage(new String(payload, StandardCharsets.UTF_8));
        }
    }

    private void parseMessage(String text) {
        try {
            JSONObject message = new JSONObject(text);
            if ("pong".equals(message.optString("type"))) {
                long sentAt = message.optLong("sentAt", 0L);
                if (sentAt > 0L) {
                    long roundTrip = Math.max(0L, System.currentTimeMillis() - sentAt);
                    mainHandler.post(() -> listener.onLatency(roundTrip));
                }
                return;
            }
            if (!"telemetry".equals(message.optString("type"))) return;
            JSONObject frame = message.optJSONObject("frame");
            if (frame == null) return;
            float speed = (float) frame.optDouble("speedKph", 0);
            float rpm = (float) frame.optDouble("rpm", 0);
            float maxRpm = (float) frame.optDouble("maxRpm", 8000);
            String gear = frame.optString("gear", "N");
            mainHandler.post(() -> listener.onTelemetry(speed, rpm, maxRpm, gear));
        } catch (Exception ignored) {
            // Ignore incomplete or non-telemetry messages.
        }
    }

    private void sendText(String text) throws IOException {
        sendFrame(0x1, text.getBytes(StandardCharsets.UTF_8));
    }

    private void sendFrame(int opcode, byte[] payload) throws IOException {
        synchronized (sendLock) {
            OutputStream stream = output;
            if (stream == null) throw new IOException("WebSocket is not connected");
            int length = payload.length;
            int headerLength = length < 126 ? 2 : length < 65536 ? 4 : 10;
            byte[] frame = new byte[headerLength + 4 + length];
            frame[0] = (byte) (0x80 | opcode);
            int offset = 2;
            if (length < 126) {
                frame[1] = (byte) (0x80 | length);
            } else if (length < 65536) {
                frame[1] = (byte) (0x80 | 126);
                frame[2] = (byte) (length >>> 8);
                frame[3] = (byte) length;
                offset = 4;
            } else {
                frame[1] = (byte) (0x80 | 127);
                long longLength = length;
                for (int index = 9; index >= 2; index -= 1) {
                    frame[index] = (byte) longLength;
                    longLength >>>= 8;
                }
                offset = 10;
            }
            byte[] mask = new byte[4];
            random.nextBytes(mask);
            System.arraycopy(mask, 0, frame, offset, 4);
            int payloadOffset = offset + 4;
            for (int index = 0; index < length; index += 1) {
                frame[payloadOffset + index] = (byte) (payload[index] ^ mask[index % 4]);
            }
            stream.write(frame);
            stream.flush();
        }
    }

    private static void readFully(InputStream input, byte[] target, int offset, int length)
        throws IOException {
        while (length > 0) {
            int count = input.read(target, offset, length);
            if (count < 0) throw new EOFException("WebSocket closed");
            offset += count;
            length -= count;
        }
    }

    private void notifyState(int state, String detail) {
        if (!running && state != STATE_DISCONNECTED) return;
        mainHandler.post(() -> listener.onConnectionState(state, detail));
    }

    private static String shortMessage(Exception error) {
        String value = error.getMessage();
        if (value == null || value.isEmpty()) value = error.getClass().getSimpleName();
        return value.length() > 80 ? value.substring(0, 80) : value;
    }

    private void closeSocket() {
        output = null;
        Socket current = socket;
        socket = null;
        if (current != null) {
            try {
                current.close();
            } catch (IOException ignored) {
                // Already closed.
            }
        }
    }
}
