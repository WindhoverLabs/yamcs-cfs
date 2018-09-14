package com.windhoverlabs.yamcs_cfs.tctm;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketException;
import java.io.IOException;
import java.net.UnknownHostException;
import java.net.InetAddress;
import java.nio.ByteBuffer;

public class CfsUdpTcFileTransfer {

    private DatagramSocket m_socket;
    private InetAddress m_address;
    private int m_port;

    /* Constructor. */
    public CfsUdpTcFileTransfer(int port, String address) throws SocketException, UnknownHostException {
        m_port = port;
        m_socket = new DatagramSocket(port);
        m_address = InetAddress.getByName(address);
    }

    /* Forward ByteBuffer to the target address and port. */
    public byte [] receive(byte rawPacket[]) throws IOException {
        DatagramPacket packet = new DatagramPacket(rawPacket, rawPacket.length);
        m_socket.receive(packet);
        return packet.getData();
    }

    /* Close socket. */
    public void close() {
        m_socket.close();
    }
}
