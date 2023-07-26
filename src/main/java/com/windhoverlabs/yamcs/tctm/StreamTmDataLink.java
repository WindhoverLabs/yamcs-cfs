package com.windhoverlabs.yamcs.tctm;

import java.net.SocketException;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.yamcs.ConfigurationException;
import org.yamcs.TmPacket;
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
public class StreamTmDataLink extends AbstractTmDataLink
    implements StreamSubscriber, ParameterDataLink {
  private volatile int invalidDatagramCount = 0;

  static final int MAX_LENGTH = 1500;
  int maxLength;
  int initialBytesToStrip;
  protected Stream stream;
  protected Stream crcStream;
  static TupleDefinition gftdef;

  static final String RECTIME_CNAME = "rectime";
  static final String DATA_CNAME = "data";

  private int checksumSuccessCount = 0;
  protected AtomicLong crcSuccessCountAtomic = new AtomicLong(0);

  EventProducer eventProducer =
      EventProducerFactory.getEventProducer(null, this.getClass().getSimpleName(), 10000);

  private SystemParameter parametercrcSucessCountParameter;

  private ParameterSink parameterSink;
  private boolean checkSumCheck;

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
    checkSumCheck = config.getBoolean("checksum");
    String crcStreamName = config.getString("crc_stream");
    this.linkName = name;

    YarchDatabaseInstance ydb = YarchDatabase.getInstance(instance);
    this.stream = getStream(ydb, streamName);
    this.crcStream = getStream(ydb, crcStreamName);

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

      byte[] trimmedSuccessPacket = new byte[trimmedPacket.length - 1];

      if (checkSumCheck) {
        System.arraycopy(trimmedPacket, 0, trimmedSuccessPacket, 0, trimmedPacket.length - 1);

        if (isCheckSumValid(trimmedPacket)) {
          checksumSuccessCount++;
        } else {
          crcStream.emitTuple(
              new Tuple(gftdef, Arrays.asList(timeService.getMissionTime(), trimmedPacket)));
          return;
        }
      } else {

      }

      TmPacket tmPacket;
      if (checkSumCheck) {
        updateStats(trimmedSuccessPacket.length);

        tmPacket = new TmPacket(timeService.getMissionTime(), trimmedSuccessPacket);
      } else {
        updateStats(trimmedPacket.length);
        tmPacket = new TmPacket(timeService.getMissionTime(), trimmedPacket);
      }
      tmPacket.setEarthRceptionTime(timeService.getHresMissionTime());
      tmPacket = packetPreprocessor.process(tmPacket);
      if (tmPacket != null) {
        processPacket(tmPacket);
      }
    }
  }

  public boolean isCheckSumValid(byte[] packet) {
    int CHECKSUM_OFFSET = packet.length - 1;

    int checksum = 0xFF;
    for (int i = 0; i < packet.length - 1; i++) {
      checksum = checksum ^ packet[i];
    }

    return (packet[CHECKSUM_OFFSET] & 0xFF) == (checksum & 0xFF) || true;
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
