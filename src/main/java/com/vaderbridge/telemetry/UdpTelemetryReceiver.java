// Сетевой UDP-сервис с поддержкой остановки
package com.vaderbridge.telemetry;

import java.io.Closeable;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketTimeoutException;

/**
 * Ответственность: прием UDP-пакетов телеметрии из сети.
 */
public class UdpTelemetryReceiver implements Closeable {

    private final int port;
    private DatagramSocket socket;
    private final byte[] internalBuffer = new byte[1024];

    public UdpTelemetryReceiver(int port) {
        this.port = port;
    }

    public void start() throws IOException {
        this.socket = new DatagramSocket(this.port);
        this.socket.setSoTimeout(1000);
    }

    public int receivePacket(byte[] targetBuffer) throws IOException {
        if (socket == null || socket.isClosed()) {
            return -1;
        }

        DatagramPacket packet = new DatagramPacket(internalBuffer, internalBuffer.length);
        try {
            socket.receive(packet);
            int len = packet.getLength();
            System.arraycopy(internalBuffer, 0, targetBuffer, 0, len);
            return len;
        } catch (SocketTimeoutException e) {
            return -1;
        }
    }

    @Override
    public void close() {
        if (socket != null && !socket.isClosed()) {
            socket.close();
        }
    }
}