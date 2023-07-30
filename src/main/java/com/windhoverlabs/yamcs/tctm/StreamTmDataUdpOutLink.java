package com.windhoverlabs.yamcs.tctm;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.yamcs.ConfigurationException;
import org.yamcs.YConfiguration;
import org.yamcs.events.EventProducer;
import org.yamcs.events.EventProducerFactory;
import org.yamcs.parameter.ParameterValue;
import org.yamcs.parameter.SystemParametersService;
import org.yamcs.protobuf.Yamcs.Value.Type;
import org.yamcs.tctm.AbstractTmDataLink;
import org.yamcs.tctm.ParameterDataLink;
import org.yamcs.tctm.ParameterSink;
import org.yamcs.xtce.SystemParameter;
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
public class StreamTmDataUdpOutLink extends AbstractTmDataLink
    implements StreamSubscriber, ParameterDataLink {
  private volatile int invalidDatagramCount = 0;

  static final int MAX_LENGTH = 1500;
  private int port;
  private String host;
  InetAddress address;
  protected Stream stream;
  protected Stream crcStream;
  static TupleDefinition gftdef;
  private DatagramSocket socket;

  static final String RECTIME_CNAME = "rectime";
  static final String DATA_CNAME = "data";

  private int checksumSuccessCount = 0;
  protected AtomicLong crcSuccessCountAtomic = new AtomicLong(0);

  EventProducer eventProducer =
      EventProducerFactory.getEventProducer(null, this.getClass().getSimpleName(), 10000);

  private SystemParameter parametercrcSucessCountParameter;

  private ParameterSink parameterSink;

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
    host = config.getString("host");
    port = config.getInt("port");

    String streamName = config.getString("in_stream");
    this.linkName = name;

    YarchDatabaseInstance ydb = YarchDatabase.getInstance(instance);
    this.stream = getStream(ydb, streamName);

    try {
      address = InetAddress.getByName(host);
    } catch (UnknownHostException e) {
      // TODO Auto-generated catch block
      e.printStackTrace();
    }

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
    try {
      socket = new DatagramSocket();
    } catch (SocketException e) {
      // TODO Auto-generated catch block
      e.printStackTrace();
    }
    notifyStarted();
  }

  @Override
  public void doStop() {
    socket.close();

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
      byte[] pktData = tuple.getColumn(DATA_CNAME);
      if (pktData == null) {
        throw new ConfigurationException("no column named '%s' in the tuple", DATA_CNAME);
      } else {
        DatagramPacket dtg = new DatagramPacket(pktData, pktData.length, address, port);
        try {
          socket.send(dtg);
          updateStats(pktData.length);
        } catch (IOException e) {
          log.warn("Error sending datagram", e);
          notifyFailed(e);
          return;
        }
      }
    }
  }

  @Override
  public void setupSystemParameters(SystemParametersService sysParamService) {
    super.setupSystemParameters(sysParamService);
    parametercrcSucessCountParameter =
        sysParamService.createSystemParameter(
            linkName + "/checksumSuccessCount",
            Type.SINT32,
            "Number of successful checksum checks");
  }

  @Override
  protected void collectSystemParameters(long time, List<ParameterValue> list) {
    super.collectSystemParameters(time, list);
    list.add(
        SystemParametersService.getPV(
            parametercrcSucessCountParameter, time, checksumSuccessCount));
  }

  @Override
  public void setParameterSink(ParameterSink parameterSink) {
    this.parameterSink = parameterSink;
  }

  protected void updateParameters(
      long gentime, String group, int seqNum, Collection<ParameterValue> params) {
    crcSuccessCountAtomic.set(checksumSuccessCount);

    parameterSink.updateParameters(gentime, group, seqNum, params);
  }
}
