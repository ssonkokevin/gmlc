package com.hamiltel.gmlc.diameter;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Diameter over TCP with the four-octet length prefix (RFC 6733 section 2.1).
 * One connection is opened, a Capabilities-Exchange performed, and requests
 * sent one at a time; a failure closes the connection so the next call starts
 * from a known state. SCTP is the normal carrier for these interfaces but has
 * no Java provider on this host, so TCP is what a lab peer must accept; that
 * limit is recorded in the README rather than hidden.
 */
public final class TcpDiameterTransport implements DiameterTransport {

    private static final int MAX_FRAME = 1 << 20;

    private final String host;
    private final int port;
    private final String originHost;
    private final String originRealm;
    private final String productName;

    private Socket socket;
    private DataInputStream in;
    private AtomicInteger hop;

    public TcpDiameterTransport(String host, int port, String originHost, String originRealm,
                                String productName) {
        this.host = host;
        this.port = port;
        this.originHost = originHost;
        this.originRealm = originRealm;
        this.productName = productName;
        this.hop = new AtomicInteger(ThreadLocalRandom.current().nextInt(1, 1 << 20));
    }

    @Override
    public synchronized DiameterMessage exchange(DiameterMessage request, Duration timeout) {
        try {
            ensureConnected(timeout);
            return send(request, timeout);
        } catch (IOException e) {
            close();
            throw new SignallingException("DIAMETER_TRANSPORT_ERROR",
                    "cannot exchange with " + host + ":" + port + ": " + e.getMessage(), e);
        }
    }

    private void ensureConnected(Duration timeout) throws IOException {
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
            exchangeCapabilities(timeout);
        } catch (IOException e) {
            try {
                fresh.close();
            } catch (IOException ignored) {
                // The original failure is the one worth reporting.
            }
            socket = null;
            in = null;
            throw e;
        }
    }

    private void exchangeCapabilities(Duration timeout) throws IOException {
        DiameterMessage cea = send(cer(), timeout);
        int result = resultCode(cea);
        if (cea.commandCode() != DiameterDictionary.CAPABILITIES_EXCHANGE || result != DiameterDictionary.DIAMETER_SUCCESS) {
            throw new IOException("peer rejected the capabilities exchange (" + DiameterDictionary.nameOf(result) + ")");
        }
    }

    private DiameterMessage send(DiameterMessage request, Duration timeout) throws IOException {
        int hopId = hop.incrementAndGet();
        int endId = ThreadLocalRandom.current().nextInt();
        DiameterMessage withIds = request.withIds(hopId, endId);
        socket.getOutputStream().write(DiameterMessage.frame(withIds.encode()));
        socket.getOutputStream().flush();
        socket.setSoTimeout((int) timeout.toMillis());
        while (true) {
            DiameterMessage incoming = readFrame();
            if (incoming.commandCode() == DiameterDictionary.CAPABILITIES_EXCHANGE && incoming.request()) {
                answerCer(incoming);
                continue;
            }
            if (incoming.hopByHopId() == hopId) {
                return incoming;
            }
            // A late answer to an earlier request: it has no caller left.
        }
    }

    private DiameterMessage readFrame() throws IOException {
        byte[] header = new byte[4];
        try {
            in.readFully(header);
        } catch (EOFException e) {
            throw new IOException("peer closed the connection", e);
        }
        long length = ((long) (header[0] & 0xFF) << 24) | ((header[1] & 0xFF) << 16)
                | ((header[2] & 0xFF) << 8) | (header[3] & 0xFF);
        if (length < DiameterMessage.HEADER_BYTES || length > MAX_FRAME) {
            throw new IOException("peer declared a frame of " + length + " octets");
        }
        byte[] payload = new byte[(int) length];
        in.readFully(payload);
        try {
            return DiameterMessage.parse(payload);
        } catch (IllegalArgumentException e) {
            throw new IOException("malformed Diameter message: " + e.getMessage(), e);
        }
    }

    private void answerCer(DiameterMessage cer) throws IOException {
        socket.getOutputStream().write(DiameterMessage.frame(
                cer.answer(cer.hopByHopId(), cer.endToEndId(), commonAvps(
                        Avp.baseU32(DiameterDictionary.RESULT_CODE, DiameterDictionary.DIAMETER_SUCCESS))).encode()));
        socket.getOutputStream().flush();
    }

    private DiameterMessage cer() {
        List<Avp> avps = new ArrayList<>(commonAvps());
        avps.add(Avp.base(DiameterDictionary.HOST_IP_ADDRESS, new byte[] {0, 1, 0, 0, 0, 0}));
        avps.add(Avp.baseU32(DiameterDictionary.VENDOR_ID, Avp.THREE_GPP));
        avps.add(Avp.baseU32(DiameterDictionary.SUPPORTED_VENDOR_ID, Avp.THREE_GPP));
        avps.add(Avp.baseUtf8(DiameterDictionary.PRODUCT_NAME, productName));
        avps.add(Avp.baseU32(DiameterDictionary.FIRMWARE_REVISION, 1));
        avps.add(Avp.baseU32(DiameterDictionary.AUTH_APPLICATION_ID, DiameterDictionary.SLH_APPLICATION_ID));
        avps.add(Avp.baseU32(DiameterDictionary.AUTH_APPLICATION_ID, DiameterDictionary.SLG_APPLICATION_ID));
        return DiameterMessage.request(DiameterDictionary.CAPABILITIES_EXCHANGE, 0, avps);
    }

    private List<Avp> commonAvps(Avp... leading) {
        List<Avp> avps = new ArrayList<>(List.of(leading));
        avps.add(Avp.baseUtf8(DiameterDictionary.ORIGIN_HOST, originHost));
        avps.add(Avp.baseUtf8(DiameterDictionary.ORIGIN_REALM, originRealm));
        return avps;
    }

    private static int resultCode(DiameterMessage message) {
        Integer result = message.integer(DiameterDictionary.RESULT_CODE);
        if (result != null) {
            return result;
        }
        return message.first(DiameterDictionary.EXPERIMENTAL_RESULT)
                .flatMap(experimental -> experimental.children().stream()
                        .filter(avp -> avp.code() == DiameterDictionary.EXPERIMENTAL_RESULT_CODE)
                        .findFirst())
                .map(Avp::asInt)
                .orElse(-1);
    }

    @Override
    public synchronized void close() {
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // Closing is best effort; the connection is unusable either way.
            }
        }
        socket = null;
        in = null;
    }
}
