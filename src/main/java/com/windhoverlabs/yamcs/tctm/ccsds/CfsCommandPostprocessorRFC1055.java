package com.windhoverlabs.yamcs.tctm.ccsds;

import static org.yamcs.cmdhistory.CommandHistoryPublisher.AcknowledgeSent_KEY;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yamcs.YConfiguration;
import org.yamcs.cmdhistory.CommandHistoryPublisher;
import org.yamcs.cmdhistory.CommandHistoryPublisher.AckStatus;
import org.yamcs.commanding.PreparedCommand;
import org.yamcs.tctm.CcsdsSeqCountFiller;
import org.yamcs.tctm.CommandPostprocessor;
import org.yamcs.utils.ByteArrayUtils;
import org.yamcs.utils.TimeEncoding;

public class CfsCommandPostprocessorRFC1055 implements CommandPostprocessor {

  private final int END = (0xc0);
  private final int ESC = (0xdb);
  private final int ESC_END = (0xdc);
  private final int ESC_ESC = (0xdd);

  static Logger log = LoggerFactory.getLogger(CfsCommandPostprocessorRFC1055.class);

  protected CcsdsSeqCountFiller seqFiller = new CcsdsSeqCountFiller();
  protected CommandHistoryPublisher commandHistoryPublisher;
  static final int CHECKSUM_OFFSET = 7;
  static final int FC_OFFSET = 6;
  static final int MIN_CMD_LENGTH = 7;
  final String yamcsInstance;
  private boolean swapChecksumFc = false;

  public CfsCommandPostprocessorRFC1055(String yamcsInstance) {
    this.yamcsInstance = yamcsInstance;
  }

  public CfsCommandPostprocessorRFC1055(String yamcsInstance, YConfiguration config) {
    this.yamcsInstance = yamcsInstance;
    this.swapChecksumFc = config.getBoolean("swapChecksumFc", false);
  }

  /** SLIP implementation of send_packet in Java. https://datatracker.ietf.org/doc/html/rfc1055 */
  @Override
  public byte[] process(PreparedCommand pc) {

    byte[] binary = preprocessCommand(pc);

    ByteArrayOutputStream payload = new ByteArrayOutputStream();

    byte[] temp = new byte[1];

    for (byte character : binary) {
      switch (Byte.toUnsignedInt(character)) {
          /* if it's the same code as an END character, we send a
           * special two character code so as not to make the
           * receiver think we sent an END
           */
        case END:
          temp[0] = (byte) (ESC);
          payload.write(temp[0]);
          temp[0] = (byte) (ESC_END);
          payload.write(temp[0]);
          break;

          /* if it's the same code as an ESC character,
           * we send a special two character code so as not
           * to make the receiver think we sent an ESC
           */
        case ESC:
          temp[0] = (byte) (ESC);
          try {
            payload.write(temp);
            temp[0] = (byte) ESC_ESC;
            payload.write(temp);
            payload.write(character);
          } catch (IOException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
          }
          break;

          /* otherwise, we just send the character
           */
        default:
          payload.write(Byte.toUnsignedInt(character));
          break;
      }
    }

    payload.write(END);
    pc.setBinary(payload.toByteArray());

    return payload.toByteArray();
  }

  // TODO:Refactor this
  private byte[] preprocessCommand(PreparedCommand pc) {
    byte[] binary = new byte[pc.getBinary().length];
    System.arraycopy(pc.getBinary(), 0, binary, 0, pc.getBinary().length);

    if (binary.length < MIN_CMD_LENGTH) {
      String msg =
          ("Short command received, length:"
              + binary.length
              + ", expected minimum length: "
              + MIN_CMD_LENGTH);
      log.warn(msg);
      long t = TimeEncoding.getWallclockTime();

      commandHistoryPublisher.publishAck(
          pc.getCommandId(), AcknowledgeSent_KEY, t, AckStatus.NOK, msg);
      commandHistoryPublisher.commandFailed(pc.getCommandId(), t, msg);
      return null;
    }

    ByteArrayUtils.encodeUnsignedShort(binary.length - 7, binary, 4); // set packet length
    int seqCount = seqFiller.fill(binary);
    commandHistoryPublisher.publish(
        pc.getCommandId(), CommandHistoryPublisher.CcsdsSeq_KEY, seqCount);

    // set the checksum
    binary[CHECKSUM_OFFSET] = 0;
    int checksum = 0xFF;
    for (int i = 0; i < binary.length; i++) {
      checksum = checksum ^ binary[i];
    }
    binary[CHECKSUM_OFFSET] = (byte) checksum;
    if (swapChecksumFc) {
      byte x = binary[CHECKSUM_OFFSET];
      binary[CHECKSUM_OFFSET] = binary[FC_OFFSET];
      binary[FC_OFFSET] = x;
    }

    commandHistoryPublisher.publish(pc.getCommandId(), PreparedCommand.CNAME_BINARY, binary);
    return binary;
  }

  @Override
  public void setCommandHistoryPublisher(CommandHistoryPublisher commandHistoryListener) {
    this.commandHistoryPublisher = commandHistoryListener;
  }
}
