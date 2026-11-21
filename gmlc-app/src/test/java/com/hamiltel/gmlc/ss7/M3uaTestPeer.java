package com.hamiltel.gmlc.ss7;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * A loopback M3UA signalling gateway for tests: it answers ASP Up and ASP
 * Active, records the SCCP user data of every DATA message, answers with what
 * the responder returns and counts the ASP Down that ends the association.
 */
public final class M3uaTestPeer implements AutoCloseable {

    private final ServerSocket server;
    private final Thread worker;
    private final Function<byte[], byte[]> responder;
    private final boolean acceptActive;
    private final List<byte[]> receivedSccp = Collections.synchronizedList(new ArrayList<>());
    private final CountDownLatch aspDown = new CountDownLatch(1);
    private volatile Socket connection;
    private volatile Exception failure;

    public M3uaTestPeer(Function<byte[], byte[]> responder) throws IOException {
        this(responder, true);
    }

    public M3uaTestPeer(Function<byte[], byte[]> responder, boolean acceptActive) throws IOException {
        this.responder = responder;
        this.acceptActive = acceptActive;
        this.server = new ServerSocket(0, 4, InetAddress.getLoopbackAddress());
        this.worker = new Thread(this::serve, "m3ua-test-peer");
        this.worker.setDaemon(true);
        this.worker.start();
    }

    public int port() {
        return server.getLocalPort();
    }

    public List<byte[]> receivedSccp() {
        return List.copyOf(receivedSccp);
    }

    public Exception failure() {
        return failure;
    }

    public boolean awaitAspDown(long seconds) throws InterruptedException {
        return aspDown.await(seconds, TimeUnit.SECONDS);
    }

    private void serve() {
        try {
            Socket socket = server.accept();
            connection = socket;
            socket.setSoTimeout(15_000);
            DataInputStream in = new DataInputStream(socket.getInputStream());
            OutputStream out = socket.getOutputStream();
            Integer routingContext = null;
            while (true) {
                M3uaMessage message = read(in);
                if (message.is(M3uaMessage.CLASS_ASPSM, M3uaMessage.TYPE_ASPUP)) {
                    write(out, M3uaMessage.aspUpAck());
                } else if (message.is(M3uaMessage.CLASS_ASPSM, M3uaMessage.TYPE_ASPDN)) {
                    write(out, M3uaMessage.aspDownAck());
                    aspDown.countDown();
                    return;
                } else if (message.is(M3uaMessage.CLASS_ASPTM, M3uaMessage.TYPE_ASPAC)) {
                    routingContext = message.routingContexts().isEmpty()
                            ? null : message.routingContexts().get(0);
                    if (acceptActive) {
                        write(out, M3uaMessage.aspActiveAck(routingContext));
                    } else {
                        // Deliberately the wrong answer, as a gateway without a
                        // matching routing key might send.
                        write(out, M3uaMessage.aspDownAck());
                    }
                } else if (message.is(M3uaMessage.CLASS_TRANSFER, M3uaMessage.TYPE_DATA)) {
                    M3uaMessage.ProtocolData protocolData = message.protocolData()
                            .orElseThrow(() -> new IllegalStateException("DATA without protocol data"));
                    receivedSccp.add(protocolData.userData());
                    byte[] answer = responder.apply(protocolData.userData());
                    if (answer != null) {
                        write(out, M3uaMessage.data(protocolData.destinationPointCode(),
                                protocolData.originatingPointCode(), protocolData.serviceIndicator(),
                                protocolData.networkIndicator(), protocolData.signallingLinkSelection(),
                                routingContext, answer));
                    }
                } else {
                    throw new IllegalStateException("the client sent class " + message.messageClass()
                            + " type " + message.type() + ", which this peer does not answer");
                }
            }
        } catch (EOFException | SocketException e) {
            // The client closed the association without ASP Down.
        } catch (Exception e) {
            failure = e;
        }
    }

    private static M3uaMessage read(DataInputStream in) throws IOException {
        byte[] header = new byte[M3uaMessage.HEADER_BYTES];
        in.readFully(header);
        int length = ((header[4] & 0xFF) << 24) | ((header[5] & 0xFF) << 16)
                | ((header[6] & 0xFF) << 8) | (header[7] & 0xFF);
        byte[] frame = new byte[length];
        System.arraycopy(header, 0, frame, 0, M3uaMessage.HEADER_BYTES);
        in.readFully(frame, M3uaMessage.HEADER_BYTES, length - M3uaMessage.HEADER_BYTES);
        return M3uaMessage.parse(frame);
    }

    private static void write(OutputStream out, M3uaMessage message) throws IOException {
        out.write(message.encode());
        out.flush();
    }

    @Override
    public void close() throws IOException {
        server.close();
        Socket socket = connection;
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // Tearing down either way.
            }
        }
        try {
            worker.join(5_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
