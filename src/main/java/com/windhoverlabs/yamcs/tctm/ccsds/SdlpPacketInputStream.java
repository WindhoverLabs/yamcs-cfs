/* Written by Mathew Benson, Windhover Labs, mbenson@windhoverlabs.com */

package com.windhoverlabs.yamcs.tctm.ccsds;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import org.yamcs.ConfigurationException;
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
 * @author Mathew Benson
 */
public class SdlpPacketInputStream implements PacketInputStream {
  DataInputStream dataInputStream;
  String asmString = "1ACFFC1D";
  byte[] asm;
  int maxLength = -1;

  int minLength = 0;
  int fixedLength = -1;

  int asmLength;
  int outOfSyncByteCount = 0;
  int inSyncByteCount = 0;

  int rcvdCaduCount = 0;
  int inSyncCaduCount = 0;

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
  // private byte[] packet;

  private int fatFrameCount = 0;

  @Override
  public void init(InputStream inputStream, YConfiguration args) {
    dataInputStream = new DataInputStream(inputStream);
    asmString = args.getString("asm", asmString);
    asmLength = asmString.length() / 2;
    minLength = args.getInt("minFrameLength", minLength);
    maxLength = args.getInt("maxFrameLength", maxLength);
    dropMalformed = args.getBoolean("dropMalformed", dropMalformed);

    /* TODO: I really want to properly use YAMCS events here, but I really
     * need the YAMCS instance to use it properly as well as the instance
     * name of the caller. The problem is I would need to change
     * the PacketInputStream API to get these. In the mean time, just use
     * the object without the instance by passing a null.
     */
    eventProducer =
        EventProducerFactory.getEventProducer(null, SdlpPacketInputStream.class.getName(), 10000);

    if (maxLength < 0) {
      throw new ConfigurationException("'maxFrameLength' must be defined.");
    }

    if (maxLength < minLength) {
      throw new ConfigurationException(
          "'maxFrameLength' ("
              + maxLength
              + ") must not be less than 'minLength' ("
              + minLength
              + ").");
    }

    if (maxLength < 0) {
      throw new ConfigurationException(
          "'maxFrameLength' (" + maxLength + ") must be greater than zero.");
    }

    if (minLength < 0) {
      throw new ConfigurationException(
          "'minFrameLength' (" + minLength + ") must be greater than zero.");
    }

    if (dropMalformed && (maxLength < 0)) {
      throw new ConfigurationException(
          "'dropMalformed' must not be 'true' unless 'maxLength' is defined.");
    }

    eventProducer.sendInfo("ASM set to " + asmString);

    asm = fromHexString(asmString);

    if (minLength == maxLength) {
      fixedLength = minLength;
    }

    outOfSyncByteCount = 0;
    inSyncByteCount = 0;
    rcvdCaduCount = 0;
    rcvdFatCaduCount = 0;
    inSyncCaduCount = 0;
    parserState = ParserState.OUT_OF_SYNC;
  }

  @Override
  public byte[] readPacket() throws IOException {
    byte[] tmpPacket = null;
    byte[] packet = null;
    byte[] asmField = new byte[asmLength];
    caduLength = 0;
    asmCursor = 0;
    boolean isFatFrame = false;
    fatFrameBytes = 0;
    //int printCount = 0;

    //System.console().printf("************************************\n");
    //System.console().printf("************************************\n");
    //System.console().printf("************************************\n");

    while (parserState != ParserState.CADU_COMPLETE) {
      switch (parserState) {
        case OUT_OF_SYNC:
          {
            /* We are totally out of sync. Start, or keep looking, for the first ASM, one byte
             * at a time.
             */
            dataInputStream.readFully(asmField, 0, 1);
        	//System.console().printf("%02x ", asmField[0]);
        	//printCount++;
        	//if(printCount >= 32) {
        	//	System.console().printf("\n");
        	//	printCount = 0;
        	//}
        	

            outOfSyncByteCount++;

            /* Is this the next value of the ASM? */
            if (Byte.compare(asm[asmCursor], asmField[0]) == 0) {
              /* Yes this is the next ASM value. Advance the cursor to the next byte. */
              asmCursor++;

              /* Have we read all of the ASM? */
              if (asmCursor >= asmLength) {
                /* Yes. Transition to the AT_CADU_START state. */
                TransitionToState(ParserState.AT_CADU_START);
                break;
              }
            } else {
              /* This is not the next ASM value. Remain in this state, but reset the
               * ASM cursor and keep looking for a fully formed ASM. */
              asmCursor = 0;
            }

            break;
          }

        case AT_CADU_START:
          {
            caduLength = 0;

            /* We just finished parsing the ASM and are at the start of a new CADU.
             * Is the CADU length fixed?
             */
            if (fixedLength > 0) {
              /* Yes it is. Go ahead and just read the fixed number of bytes. */
              packet = new byte[fixedLength];
              dataInputStream.readFully(packet, 0, fixedLength);

              caduLength = fixedLength;
              inSyncByteCount += caduLength;

              /* Now make sure the next bytes we see are the next ASM. */
              asmCursor = 0;
              TransitionToState(ParserState.PARSING_ASM);
            } else {
              /* The CADU is variable length. Start reading the contents of the CADU,
               * one byte at a time.
               */
              tmpPacket = new byte[maxLength];
              caduLength = 0;

              /* If the minimum length is configured, just go ahead and read the
               * minimum number of bytes right away.
               */
              if (minLength > 0) {
                dataInputStream.readFully(tmpPacket, 0, minLength);

                caduLength = minLength;
                inSyncByteCount += caduLength;
              }

              /* If we got this far, the CADU is variable length so start parsing the
               * CADU until we find the next ASM.
               */
              asmCursor = 0;
              TransitionToState(ParserState.PARSING_CADU);
            }

            break;
          }

        case PARSING_CADU:
          {
            /* We think we're still in sync, but we don't know when the next ASM is going to
             * appear. Start, or continue, reading the CADU 1 byte at a time.
             */
            dataInputStream.readFully(tmpPacket, caduLength, 1);
            caduLength++;
            inSyncByteCount++;

            /* Did we parse the maximum number of bytes that our CADU can be? */
            if (caduLength >= maxLength) {
              /* Yes, this is the maximum the CADU can be. Assume this is the end of the CADU
               * and start looking for the next ASM.
               */
              packet = new byte[caduLength];
              System.arraycopy(tmpPacket, 0, packet, 0, caduLength);
              asmCursor = 0;
              TransitionToState(ParserState.PARSING_ASM);
              break;
            }

            /* Now lets see if we're possibly running into another ASM. */

            /* Is the current value we just read possibly part of the next ASM? */
            if (Byte.compare(asm[asmCursor], tmpPacket[caduLength - 1]) == 0) {
              /* Yes, this is the first/next expected value of the ASM. */
              asmCursor++;

              /* Did we just find the last byte of the ASM? */
              if (asmCursor >= asmLength) {
                /* Yes, this is the last byte of the ASM. This marks the end of a CADU
                 * and the beginning of a new CADU. Return the packet (minus the ASM),
                 * and transition back to the CADU_COMPLETE state.
                 */
                int truncatedLength = caduLength - asmLength;
                packet = new byte[truncatedLength];

                System.arraycopy(tmpPacket, 0, packet, 0, truncatedLength);

                TransitionToState(ParserState.CADU_COMPLETE);
                break;
              }
            } else {
              /* No, this is not part of the ASM. Its just part of the CADU. Reset the ASM
               * cursor and keep going. */
              asmCursor = 0;
            }

            break;
          }

        case PARSING_ASM:
          {
            /* We should be parsing the ASM. Make sure it is. */
            dataInputStream.readFully(asmField, 0, 1);

            if (isFatFrame) {
              fatFrameBytes++;
            }

            /* Is the current value we just read possibly part of the next ASM? */
            if (Byte.compare(asm[asmCursor], asmField[0]) == 0) {
              asmCursor++;
              inSyncByteCount++;
              if (asmCursor >= asmLength) {
                /* Yes, this is the last byte of the ASM. This marks the end of a CADU.
                 * We only get here when the packet is a fixed length, so the packet
                 * is already set. We were just ensuring that its correct. Just transition
                 * to the CADU_COMPLETE state.
                 */
                if (isFatFrame) {
                  fatFrameCount++;
                  eventProducer.sendWarning(
                      "Received a fat frame of " + (caduLength + fatFrameBytes) + " bytes");
                }

                TransitionToState(ParserState.CADU_COMPLETE);

                break;
              }
            } else {
              /* No, this is not part of the ASM. This could either be a malformed "fat" frame, or we
               * could be totally out of sync. If the dropMalformed flag is set to true, we're going to
               * assume we're totally out of sync and just transition back to OUT_OF_SYNC. If its set to
               * false, keep parsing until we do get to a valid ASM.
               */
              asmCursor = 0;
              if (dropMalformed) {
                caduLength = 0;
                TransitionToState(ParserState.OUT_OF_SYNC);
              } else {
                isFatFrame = true;
              }
            }

            break;
          }

        default:
          {
            eventProducer.sendCritical("SDLP parser in an illegal state (" + parserState + ")");
            break;
          }
      }
    }

    /* We parsed one full CADU. Set the parser to the AT_CADU_START state, so the next parse will
     * start immediately after an ASM. Transition to the AT_CADU_START so the next parse will
     * start already in the SYNC'd state so it won't search for the ASM again.
     */
    TransitionToState(ParserState.AT_CADU_START);

	//System.console().printf("\n");
	
    return packet;
  }

  /* State transitions.  This just resets certain variables and possibly sends events.
   * This does not enforce legal transitions. */
  private void TransitionToState(ParserState newParserState) {
    switch (newParserState) {
      case OUT_OF_SYNC:
        {
          /* We're transitioning to out of sync.  Let the user know. */
          eventProducer.sendWarning(
              "Lost sync after " + inSyncCaduCount + " CADUs and " + inSyncByteCount + " bytes.");

          /* Reset the counts. */
          inSyncCaduCount = 0;
          inSyncByteCount = 0;
          outOfSyncByteCount = 0;
          break;
        }

      case AT_CADU_START:
        {
          /* Only reset inSyncByteCount and send an event if we are transitioning from the OUT_OF_SYNC state.  */
          if (ParserState.OUT_OF_SYNC == parserState) {
            eventProducer.sendInfo("Acquired sync after " + outOfSyncByteCount + " bytes.");
          }
          break;
        }

      case PARSING_CADU:
        {
          /* Do nothing. */
          break;
        }

      case PARSING_ASM:
        {
          /* Do nothing. */
          break;
        }

      case CADU_COMPLETE:
        {
          rcvdCaduCount++;
          inSyncCaduCount++;
          break;
        }
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
