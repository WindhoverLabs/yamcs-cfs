package com.windhoverlabs.yamcs.tctm;

import java.net.SocketException;
import org.yamcs.ConfigurationException;
import org.yamcs.TmPacket;
import org.yamcs.YConfiguration;
import org.yamcs.tctm.AbstractTmDataLink;
import org.yamcs.yarch.ColumnDefinition;
import org.yamcs.yarch.DataType;
import org.yamcs.yarch.Stream;
import org.yamcs.yarch.StreamSubscriber;
import org.yamcs.yarch.Tuple;
import org.yamcs.yarch.TupleDefinition;
import org.yamcs.yarch.YarchDatabase;
import org.yamcs.yarch.YarchDatabaseInstance;

/**
 * Receives telemetry packets via UDP. One UDP datagram = one TM packet.
 *
 * <p>Options:
 *
 * <ul>
 *   <li>{@code port} - the UDP port to listen to
 *   <li>{@code maxLength} - the maximum length of the datagram (and thus the TM packet length +
 *       initialBytesToStrip). If a datagram longer than this size will be received, it will be
 *       truncated. Default: 1500 (bytes)
 *   <li>{@code initialBytesToStrip} - if configured, skip that number of bytes from the beginning
 *       of the datagram. Default: 0
 * </ul>
 */
public class StreamTmDataLink extends AbstractTmDataLink implements StreamSubscriber {
  private volatile int invalidDatagramCount = 0;

  static final int MAX_LENGTH = 1500;
  int maxLength;
  int initialBytesToStrip;
  protected Stream stream;
  static TupleDefinition gftdef;

  static final String RECTIME_CNAME = "rectime";
  static final String DATA_CNAME = "data";

  static {
    gftdef = new TupleDefinition();
    gftdef.addColumn(new ColumnDefinition(RECTIME_CNAME, DataType.TIMESTAMP));
    gftdef.addColumn(new ColumnDefinition(DATA_CNAME, DataType.BINARY));
  }

  /**
   * Creates a new UDP TM Data Link
   *
   * @throws ConfigurationException if port is not defined in the configuration
   */
  @Override
  public void init(String instance, String name, YConfiguration config)
      throws ConfigurationException {
    super.init(instance, name, config);
    maxLength = config.getInt("maxLength", MAX_LENGTH);
    initialBytesToStrip = config.getInt("initialBytesToStrip", 0);

    String streamName = config.getString("in_stream");
    this.linkName = name;

    YarchDatabaseInstance ydb = YarchDatabase.getInstance(instance);
    this.stream = getStream(ydb, streamName);

    this.stream.addSubscriber(this);
  }

  private static Stream getStream(YarchDatabaseInstance ydb, String streamName) {
    Stream stream = ydb.getStream(streamName);
    if (stream == null) {
      try {
        ydb.execute("create stream " + streamName + gftdef.getStringDefinition());
        // ydb.execute("create stream " + streamName);
      } catch (Exception e) {
        throw new ConfigurationException(e);
      }
      stream = ydb.getStream(streamName);
    }
    return stream;
  }

  @Override
  public void doStart() {
    notifyStarted();
  }

  @Override
  public void doStop() {
    notifyStopped();
  }

  /** returns statistics with the number of datagram received and the number of invalid datagrams */
  @Override
  public String getDetailedStatus() {
    if (isDisabled()) {
      return "DISABLED";
    } else {
      return String.format(
          "OK %nValid datagrams received: %d%nInvalid datagrams received: %d",
          packetCount.get(), invalidDatagramCount);
    }
  }

  /** Sets the disabled to true such that getNextPacket ignores the received datagrams */
  @Override
  public void doDisable() {}

  /**
   * Sets the disabled to false such that getNextPacket does not ignore the received datagrams
   *
   * @throws SocketException
   */
  @Override
  public void doEnable() {}

  @Override
  protected Status connectionStatus() {
    return Status.OK;
  }

  @Override
  public void onTuple(Stream arg0, Tuple tuple) {
    if (isRunningAndEnabled()) {
      byte[] packet;
      packet = tuple.getColumn(DATA_CNAME);

      int trimmedPacketLength = packet.length - initialBytesToStrip;

      if (trimmedPacketLength <= 0) {
        log.error(
            "received datagram of size {} <= {} (initialBytesToStrip); ignored.",
            packet.length,
            initialBytesToStrip);
        invalidDatagramCount++;
        return;
      }

      byte[] trimmedPacket = new byte[trimmedPacketLength];
      System.arraycopy(packet, initialBytesToStrip, trimmedPacket, 0, trimmedPacketLength);
      updateStats(trimmedPacketLength);

      TmPacket tmPacket = new TmPacket(timeService.getMissionTime(), trimmedPacket);
      tmPacket.setEarthRceptionTime(timeService.getHresMissionTime());
      tmPacket = packetPreprocessor.process(tmPacket);
      if(tmPacket != null) {
          processPacket(tmPacket);
      }
    }
  }
}
