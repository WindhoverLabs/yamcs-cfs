package com.windhoverlabs.yamcs.tctm;

import com.google.common.util.concurrent.RateLimiter;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.concurrent.TimeUnit;
import org.yamcs.ConfigurationException;
import org.yamcs.YConfiguration;
import org.yamcs.tctm.ccsds.AbstractTcFrameLink;
import org.yamcs.tctm.ccsds.TcTransferFrame;
import org.yamcs.utils.StringConverter;

/**
 * Sends TC as TC frames (CCSDS 232.0-B-3) or TC frames embedded in CLTU (CCSDS 231.0-B-3).
 *
 * <p>This class implements rate limiting. args:
 *
 * <ul>
 *   <li>frameMaxRate: maximum number of command frames to send per second.
 * </ul>
 *
 * @author nm
 */
public class StreamTcFrameLink extends AbstractTcFrameLink implements Runnable {
  String host;
  int port;
  DatagramSocket socket;
  InetAddress address;
  Thread thread;
  RateLimiter rateLimiter;

  public void init(String yamcsInstance, String name, YConfiguration config) {
    super.init(yamcsInstance, name, config);
    host = config.getString("host");
    port = config.getInt("port");

    try {
      address = InetAddress.getByName(host);
    } catch (UnknownHostException e) {
      throw new ConfigurationException("Cannot resolve host '" + host + "'", e);
    }
    if (config.containsKey("frameMaxRate")) {
      rateLimiter = RateLimiter.create(config.getDouble("frameMaxRate"), 1, TimeUnit.SECONDS);
    }
  }

  @Override
  public void run() {
    while (isRunningAndEnabled()) {
      if (rateLimiter != null) {
        rateLimiter.acquire();
      }
      TcTransferFrame tf = multiplexer.getFrame();
      if (tf != null) {
        byte[] data = tf.getData();
        if (log.isTraceEnabled()) {
          log.trace("Outgoing frame data: {}", StringConverter.arrayToHexString(data, true));
        }

        if (cltuGenerator != null) {
          data = encodeCltu(tf.getVirtualChannelId(), data);

          if (log.isTraceEnabled()) {
            log.trace("Outgoing CLTU: {}", StringConverter.arrayToHexString(data, true));
          }
        }
        DatagramPacket dtg = new DatagramPacket(data, data.length, address, port);
        try {
          socket.send(dtg);
        } catch (IOException e) {
          log.warn("Error sending datagram", e);
          notifyFailed(e);
          return;
        }

        if (tf.isBypass()) {
          ackBypassFrame(tf);
        }

        frameCount++;
      }
    }
  }

  @Override
  protected void doDisable() throws Exception {
    if (thread != null) {
      thread.interrupt();
    }
    if (socket != null) {
      socket.close();
      socket = null;
    }
  }

  @Override
  protected void doEnable() throws Exception {
    socket = new DatagramSocket();
    thread = new Thread(this);
    thread.start();
  }

  @Override
  protected void doStart() {
    try {
      doEnable();
      notifyStarted();
    } catch (Exception e) {
      log.warn("Exception starting link", e);
      notifyFailed(e);
    }
  }

  @Override
  protected void doStop() {
    try {
      doDisable();
      multiplexer.quit();
      notifyStopped();
    } catch (Exception e) {
      log.warn("Exception stopping link", e);
      notifyFailed(e);
    }
  }

  @Override
  protected Status connectionStatus() {
    return (socket == null) ? Status.DISABLED : Status.OK;
  }
}
