package com.windhoverlabs.yamcs.tctm.ccsds;

import java.io.ByteArrayOutputStream;
import org.yamcs.YConfiguration;
import org.yamcs.commanding.PreparedCommand;
import org.yamcs.tctm.cfs.CfsCommandPostprocessor;

public class CfsCommandPostprocessorRFC1055 extends CfsCommandPostprocessor {

  private final byte END = (byte) 0xc0;
  private final byte ESC = (byte) 0xdb;
  private final byte ESC_END = (byte) 0xdc;
  private final byte ESC_ESC = (byte) 0xdd;

  public CfsCommandPostprocessorRFC1055(String yamcsInstance, YConfiguration config) {
    super(yamcsInstance, config);
    // TODO Auto-generated constructor stub
  }

  /** SLIP implementation of send_packet in Java. https://datatracker.ietf.org/doc/html/rfc1055 */
  @Override
  public byte[] process(PreparedCommand pc) {
    System.out.println("process$$$$$1");
    byte[] p = pc.getBinary();
    ByteArrayOutputStream payload = new ByteArrayOutputStream();

    for (byte character : p) {
      System.out.println("process$$$$$2");
      switch (character) {
          /* if it's the same code as an END character, we send a
           * special two character code so as not to make the
           * receiver think we sent an END
           */
        case END:
          payload.write(END);
          payload.write(ESC_END);
          break;

          /* if it's the same code as an ESC character,
           * we send a special two character code so as not
           * to make the receiver think we sent an ESC
           */
        case ESC:
          payload.write(ESC);
          payload.write(ESC_ESC);
          break;

          /* otherwise, we just send the character
           */
        default:
          payload.write(character);
      }
    }
    pc.setBinary(payload.toByteArray());
    return super.process(pc);
  }
}
