package com.windhoverlabs.yamcs_cfs.tctm;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketException;
import java.io.IOException;
import java.net.UnknownHostException;
import java.net.InetAddress;
import java.nio.ByteBuffer;

public class CfsUdpTmFileTransfer {

    private DatagramSocket m_socket;
    private InetAddress m_address;
    private int m_port;
    
    /* Constructor. */
    public CfsUdpTmFileTransfer(int port, String address) throws SocketException, UnknownHostException {
        m_port = port;
        m_socket = new DatagramSocket();
        m_address = InetAddress.getByName(address);
    }

    /* Forward ByteBuffer to the target address and port. */
    public void send(byte rawPacket[]) throws IOException {
        DatagramPacket packet = new DatagramPacket(rawPacket, rawPacket.length, m_address, m_port);
        m_socket.send(packet);
    }

    /* Close socket. */
    public void close() {
        m_socket.close();
    }
}
