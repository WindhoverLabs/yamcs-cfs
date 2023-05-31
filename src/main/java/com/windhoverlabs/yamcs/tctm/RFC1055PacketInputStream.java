/* Written by Mathew Benson, Windhover Labs, mbenson@windhoverlabs.com */

package com.windhoverlabs.yamcs.tctm;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import org.yamcs.ConfigurationException;
import org.yamcs.YConfiguration;
import org.yamcs.events.EventProducer;
import org.yamcs.events.EventProducerFactory;
import org.yamcs.tctm.PacketInputStream;
import org.yamcs.utils.StringConverter;

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
  String asmString = "1ACFFC1D";
  byte[] asm;
  int maxLength = -1;
  private int initialBytesToStrip;

  int minLength = 0;
  int fixedLength = -1;

  int asmLength;
  int outOfSyncByteCount = 0;
  int inSyncByteCount = 0;

  int rcvdCaduCount = 0;

  int rcvdFatCaduCount = 0;
  boolean dropMalformed = true;
  protected EventProducer eventProducer;

  enum ParserState {
    OUT_OF_SYNC,
    AT_CADU_START,
    PARSING_CADU,
    PARSING_ASM,
    CADU_COMPLETE
  }

  ParserState parserState;

  private int asmCursor;
  private int fatFrameBytes;
  private int caduLength;
  // FIXME:Temporary. Don't want to be exposing this packet so easily.
  private byte[] packet;

  private int fatFrameCount = 0;

  @Override
  public void init(InputStream inputStream, YConfiguration args) {
    dataInputStream = new DataInputStream(inputStream);
    asmString = args.getString("asm", asmString);
    asmLength = asmString.length() / 2;
    minLength = args.getInt("minLength", minLength);
    maxLength = args.getInt("maxLength", maxLength);
    dropMalformed = args.getBoolean("dropMalformed", dropMalformed);
    initialBytesToStrip = args.getInt("initialBytesToStrip", 0);

    /* TODO: I really want to properly use YAMCS events here, but I really
     * need the YAMCS instance to use it properly as well as the instance
     * name of the caller. The problem is I would need to change
     * the PacketInputStream API to get these. In the mean time, just use
     * the object without the instance by passing a null.
     */
    eventProducer =
        EventProducerFactory.getEventProducer(null, RFC1055PacketInputStream.class.getName(), 0);

    if (maxLength < 0) {
      throw new ConfigurationException("'maxLength' must be defined.");
    }

    if (maxLength < minLength) {
      throw new ConfigurationException(
          "'maxLength' (" + maxLength + ") must not be less than 'minLength' (" + minLength + ").");
    }

    if (maxLength < 0) {
      throw new ConfigurationException(
          "'maxLength' (" + maxLength + ") must be greater than zero.");
    }

    if (minLength < 0) {
      throw new ConfigurationException(
          "'minLength' (" + maxLength + ") must be greater than zero.");
    }

    if (dropMalformed && (maxLength < 0)) {
      throw new ConfigurationException(
          "'dropMalformed' must not be 'true' unless 'maxLength' is defined.");
    }

    asm = fromHexString(asmString);

    if (minLength == maxLength) {
      fixedLength = minLength;
    }

    outOfSyncByteCount = 0;
    inSyncByteCount = 0;
    rcvdCaduCount = 0;
    rcvdFatCaduCount = 0;
    parserState = ParserState.OUT_OF_SYNC;
  }

  @Override
  public byte[] readPacket() throws IOException {
    byte[] packet = getPayload(dataInputStream);

    byte[] trimmedPacket = new byte[packet.length - initialBytesToStrip];

    System.arraycopy(trimmedPacket, initialBytesToStrip, packet, 0, initialBytesToStrip);

    return trimmedPacket;
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
          return payload.toByteArray();

          /* if it's the same code as an ESC character, wait
           * and get another character and then figure out
           * what to store in the packet based on that.
           */
          /*Fallthrough*/
        case ESC:
          data.readFully(nextByte, 0, 1);
          /* if "c" is not one of these two, then we
           * have a protocol violation.  The best bet
           * seems to be to leave the byte alone and
           * just stuff it into the packet
           */
          switch (nextByte[0]) {
            case ESC_END:
              nextByte[0] = END;
              break;
            case ESC_ESC:
              nextByte[0] = ESC;
              break;
          }

          /* here we fall into the default handler and let
           * it store the byte for us
           */
        default:
          payload.write(nextByte[0]);
          received++;
      }
    }
  }

  /* State transitions.  This just resets certain variables and possibly sends events.
   * This does not enforce legal transitions. */
  private void TransitionToState(ParserState newParserState) {
    switch (newParserState) {
      case OUT_OF_SYNC:
        outOfSyncByteCount = 0;

        eventProducer.sendWarning(
            "Lost sync after " + rcvdCaduCount + " CADUs and " + inSyncByteCount + " bytes.");
        break;

      case AT_CADU_START:
        /* Only reset inSyncByteCount and send an event if we are transitioning from the OUT_OF_SYNC state.  */
        if (ParserState.OUT_OF_SYNC == parserState) {
          inSyncByteCount = 0;

          eventProducer.sendInfo("Acquired sync after " + outOfSyncByteCount + " bytes.");
        }
        break;

      case PARSING_CADU:
        /* Do nothing. */
        break;

      case PARSING_ASM:
        /* Do nothing. */
        break;

      case CADU_COMPLETE:
        rcvdCaduCount++;
        break;
    }

    parserState = newParserState;
  }

  @Override
  public void close() throws IOException {
    dataInputStream.close();
  }

  private static byte[] fromHexString(final String encoded) throws IllegalArgumentException {
    if ((encoded.length() % 2) != 0)
      throw new IllegalArgumentException("Input string must contain an even number of characters");

    final byte result[] = new byte[encoded.length() / 2];

    final char enc[] = encoded.toCharArray();

    for (int i = 0; i < enc.length; i += 2) {
      StringBuilder curr = new StringBuilder(2);
      curr.append(enc[i]).append(enc[i + 1]);
      result[i / 2] = (byte) Integer.parseInt(curr.toString(), 16);
    }

    return result;
  }

  /**
   * Getter methods for understanding the state of the parser. Very useful when exposed to the
   * server as system parameters.
   *
   * @return
   */
  public int getOutOfSyncByteCount() {
    return outOfSyncByteCount;
  }

  public int getInSyncByteCount() {
    return inSyncByteCount;
  }

  public int getAsmCursor() {
    return asmCursor;
  }

  public ParserState getParserState() {
    return parserState;
  }

  public int getFatFrameBytes() {
    return fatFrameBytes;
  }

  public int getCaduLength() {
    return caduLength;
  }

  public int getFixedLength() {
    return fixedLength;
  }

  public int getFatFrameCount() {
    return fatFrameCount;
  }

  public String getPacket() {
    return StringConverter.arrayToHexString(packet);
  }

  public int getRcvdCaduCount() {
    return rcvdCaduCount;
  }

  /**
   * @param fixedLength
   * @throws Exception if minLength and maxLength are not equal.
   * @throws IllegalArgumentException if fixedLength is less than 0.
   */
  public void setFixedLength(int fixedLength) throws Exception {

    if (this.minLength == this.maxLength) {
      if (fixedLength > 0) {
        this.fixedLength = fixedLength;
        this.maxLength = fixedLength;
        this.minLength = fixedLength;
      } else {
        throw new IllegalArgumentException("fixedLength must be greater than 0.");
      }
    } else {
      throw new Exception("minLength and maxLength must be equal in order to set fixedLength.");
    }
  }

  public int getMinLength() {
    return minLength;
  }

  public void setMinLength(int minLength) {
    this.minLength = minLength;
  }

  public int getMaxLength() {
    return maxLength;
  }

  public void setMaxLength(int maxLength) {
    this.maxLength = maxLength;
  }

  /** Resets rcvdCaduCount and fatFrameCount to 0. */
  public void resetCounts() {
    rcvdCaduCount = 0;
    fatFrameCount = 0;
  }
}
