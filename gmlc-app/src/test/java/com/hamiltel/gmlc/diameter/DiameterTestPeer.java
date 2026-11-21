package com.hamiltel.gmlc.diameter;

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
import java.util.function.Function;

/**
 * A loopback Diameter peer for tests: it performs the capabilities exchange,
 * records every request and answers with whatever the responder returns.
 */
final class DiameterTestPeer implements AutoCloseable {

    private final ServerSocket server;
    private final Thread worker;
    private final List<DiameterMessage> received = Collections.synchronizedList(new ArrayList<>());
    private final Function<DiameterMessage, List<Avp>> responder;
    private volatile Exception failure;

    DiameterTestPeer(Function<DiameterMessage, List<Avp>> responder) throws IOException {
        this.responder = responder;
        this.server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        this.worker = new Thread(this::serve, "diameter-test-peer");
        this.worker.setDaemon(true);
        this.worker.start();
    }

    int port() {
        return server.getLocalPort();
    }

    List<DiameterMessage> received() {
        return List.copyOf(received);
    }

    Exception failure() {
        return failure;
    }

    private void serve() {
        try (Socket socket = server.accept()) {
            socket.setSoTimeout(15_000);
            DataInputStream in = new DataInputStream(socket.getInputStream());
            OutputStream out = socket.getOutputStream();

            DiameterMessage cer = readFrame(in);
            if (cer.commandCode() != DiameterDictionary.CAPABILITIES_EXCHANGE || !cer.request()) {
                throw new IllegalStateException("expected a Capabilities-Exchange-Request");
            }
            write(out, cer.answer(cer.hopByHopId(), cer.endToEndId(), List.of(
                    Avp.baseU32(DiameterDictionary.RESULT_CODE, DiameterDictionary.DIAMETER_SUCCESS),
                    Avp.baseUtf8(DiameterDictionary.ORIGIN_HOST, "hss.lab.local"),
                    Avp.baseUtf8(DiameterDictionary.ORIGIN_REALM, "lab.local"))));

            while (true) {
                DiameterMessage request = readFrame(in);
                received.add(request);
                List<Avp> answer = new ArrayList<>();
                answer.add(Avp.baseUtf8(DiameterDictionary.SESSION_ID,
                        request.text(DiameterDictionary.SESSION_ID)));
                answer.add(Avp.baseUtf8(DiameterDictionary.ORIGIN_HOST, "hss.lab.local"));
                answer.add(Avp.baseUtf8(DiameterDictionary.ORIGIN_REALM, "lab.local"));
                answer.addAll(responder.apply(request));
                write(out, request.answer(request.hopByHopId(), request.endToEndId(), answer));
            }
        } catch (EOFException | SocketException e) {
            // The client closed the connection: the normal end of a test.
        } catch (Exception e) {
            failure = e;
        }
    }

    private static DiameterMessage readFrame(DataInputStream in) throws IOException {
        byte[] header = new byte[4];
        in.readFully(header);
        int length = ((header[0] & 0xFF) << 24) | ((header[1] & 0xFF) << 16)
                | ((header[2] & 0xFF) << 8) | (header[3] & 0xFF);
        byte[] payload = new byte[length];
        in.readFully(payload);
        return DiameterMessage.parse(payload);
    }

    private static void write(OutputStream out, DiameterMessage message) throws IOException {
        out.write(DiameterMessage.frame(message.encode()));
        out.flush();
    }

    @Override
    public void close() throws IOException {
        server.close();
        try {
            worker.join(5_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
