/* Written by Mathew Benson, Windhover Labs, mbenson@windhoverlabs.com */

package com.windhoverlabs.yamcs.tctm;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import org.yamcs.YConfiguration;
import org.yamcs.events.EventProducer;
import org.yamcs.events.EventProducerFactory;
import org.yamcs.tctm.PacketInputStream;

/**
 * Reads CCSDS packets from an input stream: This packet input stream reads and verifies the data 1
 * piece at a time, when possible. This improves data integrity and reliability when parsing streams
 * that are lossy or when it is not guaranteed to begin at the beginning of a packet. This is a
 * stateful design that will reset back to the initial unsynchronized state when it detects the
 * possibility that its parsing invalid data. For example, if the integrator knows the stream will
 * not contain any packet greater than a certain size, not just the maximum CCSDS size, the maximum
 * size can bet set as a configurable parameter. In this case, when a length parameter is read that
 * exceeds the maximum size, the parser will detect this as an out of sync condition, and reset back
 * to the initial out of sync state.
 *
 * @author Lorenzo Gomez
 */
public class RFC1055PacketInputStream implements PacketInputStream {
  private final byte END = (byte) 0xc0;
  private final byte ESC = (byte) 0xdb;
  private final byte ESC_END = (byte) 0xdc;
  private final byte ESC_ESC = (byte) 0xdd;
  DataInputStream dataInputStream;

  private byte[] packet;
  protected EventProducer eventProducer;

  @Override
  public void init(InputStream inputStream, YConfiguration args) {
    dataInputStream = new DataInputStream(inputStream);

    /* TODO: I really want to properly use YAMCS events here, but I really
     * need the YAMCS instance to use it properly as well as the instance
     * name of the caller. The problem is I would need to change
     * the PacketInputStream API to get these. In the mean time, just use
     * the object without the instance by passing a null.
     */
    eventProducer =
        EventProducerFactory.getEventProducer(null, RFC1055PacketInputStream.class.getName(), 0);
  }

  @Override
  public byte[] readPacket() throws IOException {
    byte[] packet = getPayload(dataInputStream);

    return packet;
  }

  /**
   * This implements the receiving side of RFC 1055. For more information on the standard, go to
   * https://datatracker.ietf.org/doc/html/rfc1055 This method is based on the snippet from RFC
   * 1055, Page 5.
   *
   * <p>WARNING: Do not use this code yet. It needs plenty of refactoring.
   */
  protected byte[] getPayload(DataInputStream data) throws IOException {
    byte[] nextByte = new byte[1];
    int received = 0;

    ByteArrayOutputStream payload = new ByteArrayOutputStream();
    /* sit in a loop reading bytes until we put together
     * a whole packet.
     * Make sure not to copy them into the packet if we
     * run out of room.
     */
    // TODO:Add a MAX_PACKET_SIZE configuration arg.
    while (true) {
      /* get a character to process
       */
      data.readFully(nextByte, 0, 1);

      /* handle bytestuffing if necessary
       */

      switch (nextByte[0]) {

          /* if it's an END character then we're done with
           * the packet
           */
        case END:
          /* a minor optimization: if there is no
           * data in the packet, ignore it. This is
           * meant to avoid bothering IP with all
           * the empty packets generated by the
           * duplicate END characters which are in
           * turn sent to try to detect line noise.
           *   if(received)
           *     return received;
           *    else
           *     break;
           */
          payload.write(nextByte[0]);
          return payload.toByteArray();

        default:
          payload.write(nextByte[0]);
          received++;
      }
    }
  }

  @Override
  public void close() throws IOException {
    dataInputStream.close();
  }
}
