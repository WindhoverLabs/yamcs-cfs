package com.windhoverlabs.yamcs.tctm;

import java.io.IOException;
import java.io.InputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketException;
import java.util.Arrays;
import org.yamcs.ConfigurationException;
import org.yamcs.YConfiguration;
import org.yamcs.tctm.CcsdsPacketInputStream;
import org.yamcs.tctm.PacketInputStream;
import org.yamcs.tctm.ccsds.AbstractTmFrameLink;
import org.yamcs.utils.StringConverter;
import org.yamcs.utils.YObjectLoader;

/**
 * Receives telemetry fames via UDP. One UDP datagram = one TM frame.
 *
 * @author nm
 */
public class UdpTmFrameLink extends AbstractTmFrameLink implements Runnable {
  private volatile int invalidDatagramCount = 0;

  private DatagramSocket tmSocket;
  private int port;

  DatagramPacket datagram;
  String packetPreprocessorClassName;
  Object packetPreprocessorArgs;
  Thread thread;

  String packetInputStreamClassName;
  YConfiguration packetInputStreamArgs;
  PacketInputStream packetInputStream;

  private InputStream targetStream;

  /**
   * Creates a new UDP Frame Data Link
   *
   * @throws ConfigurationException if port is not defined in the configuration
   */
  public void init(String instance, String name, YConfiguration config)
      throws ConfigurationException {
    super.init(instance, name, config);
    port = config.getInt("port");
    //TODO:Move this "+ 4" nonsense to configuration
    int maxLength = frameHandler.getMaxFrameSize() + 4;
    datagram = new DatagramPacket(new byte[maxLength], maxLength);

    if (config.containsKey("packetInputStreamClassName")) {
      this.packetInputStreamClassName = config.getString("packetInputStreamClassName");
      this.packetInputStreamArgs = config.getConfig("packetInputStreamArgs");
    } else {
      this.packetInputStreamClassName = CcsdsPacketInputStream.class.getName();
      this.packetInputStreamArgs = YConfiguration.emptyConfig();
    }

    try {
      packetInputStream = YObjectLoader.loadObject(packetInputStreamClassName);
    } catch (ConfigurationException e) {
      log.error("Cannot instantiate the packetInput stream", e);
      throw e;
    }
    //    try {
    ////      targetStream = ByteSource.wrap(datagram.getData()).openStream();
    ////      packetInputStream.init(targetStream, packetInputStreamArgs);
    ////      targetStream.close();
    //    } catch (IOException e) {
    //      // TODO Auto-generated catch block
    //      e.printStackTrace();
    //    }
  }

  @Override
  public void doStart() {
    if (!isDisabled()) {
      try {
        tmSocket = new DatagramSocket(port);
        new Thread(this).start();
      } catch (SocketException e) {
        notifyFailed(e);
      }
    }
    notifyStarted();
  }

  @Override
  public void doStop() {
    tmSocket.close();
    notifyStopped();
  }

  @Override
  public void run() {
    while (isRunningAndEnabled()) {
      try {
        tmSocket.receive(datagram);

        if (log.isTraceEnabled()) {
          log.trace(
              "Received datagram of length {}: {}",
              datagram.getLength(),
              StringConverter.arrayToHexString(
                  datagram.getData(), datagram.getOffset(), datagram.getLength(), true));
        }

        byte[] packet = Arrays.copyOfRange(datagram.getData(), 4, datagram.getLength());
        System.out.println(org.yamcs.utils.StringConverter.arrayToHexString(packet, 0, 4));
        System.out.println("length of packet-->" + packet.length);
        System.out.println("datagram.getLength()-->" + (datagram.getLength() - 4));
        handleFrame(timeService.getHresMissionTime(), packet, 0, datagram.getLength() - 4);

      } catch (IOException e) {
        if (!isRunningAndEnabled()) {
          break;
        }
        log.warn("exception {} thrown when reading from the UDP socket at port {}", e, port);
      } catch (Exception e) {
        log.error("Error processing frame", e);
      }
    }
  }

  /** returns statistics with the number of datagram received and the number of invalid datagrams */
  @Override
  public String getDetailedStatus() {
    if (isDisabled()) {
      return "DISABLED";
    } else {
      return String.format(
          "OK (%s) %nValid datagrams received: %d%nInvalid datagrams received: %d",
          port, frameCount.get(), invalidDatagramCount);
    }
  }

  @Override
  protected void doDisable() {
    if (tmSocket != null) {
      tmSocket.close();
      tmSocket = null;
    }
  }

  @Override
  protected void doEnable() throws SocketException {
    tmSocket = new DatagramSocket(port);
    new Thread(this).start();
  }

  @Override
  protected Status connectionStatus() {
    return Status.OK;
  }
}
