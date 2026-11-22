package com.hamiltel.gmlc.ss7;

import com.hamiltel.gmlc.map.MapException;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;

/**
 * M3UA framing over a TCP stream. RFC 4666 puts M3UA on SCTP; the JVM on this
 * host has no SCTP provider, so this transport speaks the same messages over
 * TCP and a lab peer must be configured to accept that. It is not a substitute
 * for SCTP on a production STP: multi-homing and the SCTP-level failover are
 * absent, and that limit is recorded in the README instead of hidden.
 *
 * <p>On connect the link performs ASP Up, then ASP Active for the configured
 * routing context. ASP Up is refused through {@code M3UA_HANDSHAKE}, a peer
 * Error message through {@code M3UA_ERROR}, and a broken stream through
 * {@code M3UA_TRANSPORT_ERROR}; in every case the socket is dropped so the
 * next call starts from a known state.
 */
public final class TcpM3uaTransport implements M3uaTransport {

    private static final int MAX_FRAME = 1 << 20;

    private final String host;
    private final int port;
    private final int localPointCode;
    private final int networkIndicator;
    private final Integer routingContext;
    private final String infoString;

    private Socket socket;
    private DataInputStream in;
    private OutputStream out;
    private int signallingLinkSelection;

    public TcpM3uaTransport(String host, int port, int localPointCode,
                            int networkIndicator, Integer routingContext) {
        this(host, port, localPointCode, networkIndicator, routingContext, "GMLC");
    }

    public TcpM3uaTransport(String host, int port, int localPointCode,
                            int networkIndicator, Integer routingContext, String infoString) {
        this.host = host;
        this.port = port;
        this.localPointCode = localPointCode;
        this.networkIndicator = networkIndicator;
        this.routingContext = routingContext;
        this.infoString = infoString;
    }

    @Override
    public synchronized Sccp.UnitData exchange(int destinationPointCode, Sccp.UnitData request,
                                               Duration timeout) {
        if (destinationPointCode <= 0) {
            throw new MapException("SCCP_NO_ROUTE",
                    "no destination point code for " + request.calledParty());
        }
        byte[] sccp = request.encode();
        try {
            ensureActive(timeout);
            write(M3uaMessage.data(localPointCode, destinationPointCode, M3uaMessage.SI_SCCP,
                    networkIndicator, nextSignallingLinkSelection(), routingContext, sccp));
            while (true) {
                M3uaMessage incoming = read(timeout);
                if (incoming.is(M3uaMessage.CLASS_ASPSM, M3uaMessage.TYPE_BEAT)) {
                    write(M3uaMessage.beatAck());
                    continue;
                }
                if (incoming.messageClass() == M3uaMessage.CLASS_ERROR) {
                    throw new MapException("M3UA_ERROR",
                            "the peer reported error code " + incoming.errorCode());
                }
                if (incoming.is(M3uaMessage.CLASS_ASPSM, M3uaMessage.TYPE_ASPDN)) {
                    throw new MapException("M3UA_PEER_DOWN", "the peer shut the association down");
                }
                if (incoming.is(M3uaMessage.CLASS_TRANSFER, M3uaMessage.TYPE_DATA)) {
                    M3uaMessage.ProtocolData protocolData = incoming.protocolData()
                            .orElseThrow(() -> new MapException("M3UA_PROTOCOL_DATA",
                                    "a DATA message arrived without protocol data"));
                    if (protocolData.serviceIndicator() != M3uaMessage.SI_SCCP) {
                        // Another user part on the same association: not ours to answer.
                        continue;
                    }
                    return Sccp.UnitData.parse(protocolData.userData());
                }
                throw new MapException("M3UA_UNEXPECTED", "the peer sent "
                        + describe(incoming) + " during an exchange");
            }
        } catch (IOException e) {
            closeQuietly();
            throw new MapException("M3UA_TRANSPORT_ERROR",
                    "cannot exchange with " + host + ":" + port + ": " + e.getMessage(), e);
        } catch (MapException e) {
            closeQuietly();
            throw e;
        }
    }

    private void ensureActive(Duration timeout) throws IOException {
        if (socket != null && !socket.isClosed()) {
            return;
        }
        Socket fresh = new Socket();
        try {
            fresh.connect(new InetSocketAddress(host, port), (int) timeout.toMillis());
            fresh.setSoTimeout((int) timeout.toMillis());
            fresh.setTcpNoDelay(true);
            socket = fresh;
            in = new DataInputStream(fresh.getInputStream());
            out = fresh.getOutputStream();
            write(M3uaMessage.aspUp(localPointCode, infoString));
            await(M3uaMessage.CLASS_ASPSM, M3uaMessage.TYPE_ASPUP_ACK, timeout, "ASP Up");
            write(M3uaMessage.aspActive(routingContext));
            M3uaMessage active = await(M3uaMessage.CLASS_ASPTM, M3uaMessage.TYPE_ASPAC_ACK,
                    timeout, "ASP Active");
            if (routingContext != null && !active.routingContexts().contains(routingContext)) {
                throw new MapException("M3UA_HANDSHAKE",
                        "the peer acknowledged routing contexts " + active.routingContexts()
                                + " instead of " + routingContext);
            }
        } catch (IOException | RuntimeException e) {
            closeQuietly();
            throw e;
        }
    }

    private M3uaMessage await(int expectedClass, int expectedType, Duration timeout, String phase)
            throws IOException {
        while (true) {
            M3uaMessage message = read(timeout);
            if (message.is(expectedClass, expectedType)) {
                return message;
            }
            if (message.is(M3uaMessage.CLASS_ASPSM, M3uaMessage.TYPE_BEAT)) {
                write(M3uaMessage.beatAck());
                continue;
            }
            if (message.messageClass() == M3uaMessage.CLASS_ERROR) {
                throw new MapException("M3UA_ERROR",
                        "the peer refused " + phase + " with error code " + message.errorCode());
            }
            throw new MapException("M3UA_HANDSHAKE",
                    "the peer answered " + describe(message) + " to " + phase);
        }
    }

    private M3uaMessage read(Duration timeout) throws IOException {
        socket.setSoTimeout((int) timeout.toMillis());
        byte[] header = new byte[M3uaMessage.HEADER_BYTES];
        in.readFully(header);
        long length = ((long) (header[4] & 0xFF) << 24) | ((header[5] & 0xFF) << 16)
                | ((header[6] & 0xFF) << 8) | (header[7] & 0xFF);
        if (length < M3uaMessage.HEADER_BYTES || length > MAX_FRAME) {
            throw new IOException("the peer declared a frame of " + length + " octets");
        }
        byte[] frame = new byte[(int) length];
        System.arraycopy(header, 0, frame, 0, M3uaMessage.HEADER_BYTES);
        in.readFully(frame, M3uaMessage.HEADER_BYTES, (int) length - M3uaMessage.HEADER_BYTES);
        try {
            return M3uaMessage.parse(frame);
        } catch (MapException e) {
            throw new IOException("malformed M3UA message: " + e.getMessage(), e);
        }
    }

    private void write(M3uaMessage message) throws IOException {
        out.write(message.encode());
        out.flush();
    }

    private int nextSignallingLinkSelection() {
        signallingLinkSelection = (signallingLinkSelection + 1) & 0x0F;
        return signallingLinkSelection;
    }

    private static String describe(M3uaMessage message) {
        return "class " + message.messageClass() + " type " + message.type();
    }

    @Override
    public synchronized void close() {
        if (socket == null || socket.isClosed()) {
            closeQuietly();
            return;
        }
        try {
            socket.setSoTimeout(1000);
            write(M3uaMessage.aspDown(localPointCode));
            read(Duration.ofSeconds(1));
        } catch (IOException | RuntimeException ignored) {
            // Some gateways drop the association on the first ASP Down octet.
        } finally {
            closeQuietly();
        }
    }

    private void closeQuietly() {
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // Closing is best effort; the association is unusable either way.
            }
        }
        socket = null;
        in = null;
        out = null;
    }
}
