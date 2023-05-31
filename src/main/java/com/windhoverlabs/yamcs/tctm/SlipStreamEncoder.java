package com.windhoverlabs.yamcs.tctm;

import com.google.common.util.concurrent.RateLimiter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.yamcs.AbstractYamcsService;
import org.yamcs.ConfigurationException;
import org.yamcs.InitException;
import org.yamcs.Spec;
import org.yamcs.TmPacket;
import org.yamcs.YConfiguration;
import org.yamcs.YamcsServer;
import org.yamcs.events.EventProducer;
import org.yamcs.events.EventProducerFactory;
import org.yamcs.logging.Log;
import org.yamcs.parameter.SystemParametersProducer;
import org.yamcs.tctm.Link;
import org.yamcs.time.SimulationTimeService;
import org.yamcs.time.TimeService;
import org.yamcs.utils.DataRateMeter;
import org.yamcs.utils.StringConverter;
import org.yamcs.yarch.ColumnDefinition;
import org.yamcs.yarch.DataType;
import org.yamcs.yarch.Stream;
import org.yamcs.yarch.StreamSubscriber;
import org.yamcs.yarch.Tuple;
import org.yamcs.yarch.TupleDefinition;
import org.yamcs.yarch.YarchDatabase;
import org.yamcs.yarch.YarchDatabaseInstance;

/**
 * Receives telemetry fames via UDP. One UDP datagram = one TM frame.
 *
 * @author nm
 */
public class SlipStreamEncoder extends AbstractYamcsService
    implements Link, StreamSubscriber, SystemParametersProducer {
  private final byte END = (byte) 0xc0;
  private final byte ESC = (byte) 0xdb;
  private final byte ESC_END = (byte) 0xdc;
  private final byte ESC_ESC = (byte) 0xdd;
  RateLimiter outRateLimiter;
  protected YConfiguration config;
  protected String linkName;
  protected AtomicBoolean disabled = new AtomicBoolean(false);
  protected Log log;
  protected EventProducer eventProducer;
  protected TimeService timeService;
  protected AtomicLong inPacketCount = new AtomicLong(0);
  protected AtomicLong outPacketCount = new AtomicLong(0);
  protected boolean updateSimulationTime;
  DataRateMeter inPacketRateMeter = new DataRateMeter();
  DataRateMeter outPacketRateMeter = new DataRateMeter();
  DataRateMeter inDataRateMeter = new DataRateMeter();
  DataRateMeter outDataRateMeter = new DataRateMeter();
  protected PacketPreprocessor packetPreprocessor;
  protected Stream inStream;
  protected Stream outStream;

  // FIXME:Temporary. Don't want to be exposing this packet so easily.
  private byte[] packet;

  static TupleDefinition gftdef;

  static final String RECTIME_CNAME = "rectime";
  static final String DATA_CNAME = "data";

  static {
    gftdef = new TupleDefinition();
    gftdef.addColumn(new ColumnDefinition(RECTIME_CNAME, DataType.TIMESTAMP));
    gftdef.addColumn(new ColumnDefinition(DATA_CNAME, DataType.BINARY));
  }

  /**
   * Creates a new UDP Frame Data Link
   *
   * @throws ConfigurationException if port is not defined in the configuration
   */
  public void init(String instance, String name, YConfiguration config) {
    try {
      super.init(instance, name, config);
    } catch (InitException e1) {
      // TODO Auto-generated catch block
      e1.printStackTrace();
    }

    this.linkName = name;
    this.config = config;
    log = new Log(getClass(), instance);
    log.setContext(name);
    eventProducer = EventProducerFactory.getEventProducer(instance, name, 10000);
    this.timeService = YamcsServer.getTimeService(instance);
    String inStreamName = config.getString("in_stream");
    String outStreamName = config.getString("out_stream");

    YarchDatabaseInstance ydb = YarchDatabase.getInstance(instance);
    this.inStream = getStream(ydb, inStreamName);
    this.outStream = getStream(ydb, outStreamName);

    this.inStream.addSubscriber(this);

    if (config.containsKey("frameMaxRate")) {
      outRateLimiter = RateLimiter.create(config.getDouble("frameMaxRate"), 1, TimeUnit.SECONDS);
    }

    updateSimulationTime = config.getBoolean("updateSimulationTime", false);
    if (updateSimulationTime) {
      if (timeService instanceof SimulationTimeService) {
        SimulationTimeService sts = (SimulationTimeService) timeService;
        sts.setTime0(0);
      } else {
        throw new ConfigurationException(
            "updateSimulationTime can only be used together with SimulationTimeService "
                + "(add 'timeService: org.yamcs.time.SimulationTimeService' in yamcs.<instance>.yaml)");
      }
    }
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
  public YConfiguration getConfig() {
    return config;
  }

  @Override
  public String getName() {
    return linkName;
  }

  @Override
  public void resetCounters() {
    inPacketCount.set(0);
    outPacketCount.set(0);
  }

  @Override
  public void doStart() {
    if (!isDisabled()) {
      // new Thread(this).start();
    }
    notifyStarted();
  }

  @Override
  public void doStop() {
    notifyStopped();
  }

  public boolean isRunningAndEnabled() {
    State state = state();
    return (state == State.RUNNING || state == State.STARTING) && !disabled.get();
  }

  /**
   * Sends the packet downstream for processing.
   *
   * <p>Starting in Yamcs 5.2, if the updateSimulationTime option is set on the link configuration,
   *
   * <ul>
   *   <li>the timeService is expected to be SimulationTimeService
   *   <li>at initialization, the time0 is set to 0
   *   <li>upon each packet received, the generationTime (as set by the pre-processor) is used to
   *       update the simulation elapsed time
   * </ul>
   *
   * <p>Should be called by all sub-classes (instead of directly calling {@link
   * TmSink#processPacket(TmPacket)}
   *
   * @param tmpkt
   */
  protected void processPacket(TmPacket tmpkt) {
    long rectime = tmpkt.getReceptionTime();
    byte byteArray[] = tmpkt.getPacket();

    inStream.emitTuple(new Tuple(gftdef, Arrays.asList(rectime, byteArray)));

    if (updateSimulationTime) {
      SimulationTimeService sts = (SimulationTimeService) timeService;
      if (!tmpkt.isInvalid()) {
        sts.setSimElapsedTime(tmpkt.getGenerationTime());
      }
    }
  }

  /**
   * called when a new packet is received to update the statistics
   *
   * @param packetSize
   */
  protected void updateInStats(int packetSize) {
    inPacketCount.getAndIncrement();
    inPacketRateMeter.mark(1);
    inDataRateMeter.mark(packetSize);
  }

  /**
   * called when a new packet is sent to update the statistics
   *
   * @param packetSize
   */
  protected void updateOutStats(int packetSize) {
    outPacketCount.getAndIncrement();
    outPacketRateMeter.mark(1);
    outDataRateMeter.mark(packetSize);
  }

  @Override
  public void disable() {
    boolean b = disabled.getAndSet(true);
    if (!b) {
      try {
        /* TODO */
        // doDisable();
      } catch (Exception e) {
        disabled.set(false);
        log.warn("Failed to disable link", e);
      }
    }
  }

  /**
   * This implements the receiving side of RFC 1055. For more information on the standard, go to
   * https://datatracker.ietf.org/doc/html/rfc1055 This method is based on the snippet from RFC
   * 1055, Page 5.
   *
   * <p>WARNING: Do not use this code yet. It needs plenty of refactoring.
   */
  protected byte[] encodeMessage(byte[] pktData) throws IOException {
    ByteArrayOutputStream payload = new ByteArrayOutputStream();

    byte[] temp = new byte[1];

    payload.write(END);

    for (byte character : pktData) {
      switch (character) {
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

    return payload.toByteArray();
  }

  public String getPacket() {
    return StringConverter.arrayToHexString(packet);
  }

  @Override
  public void enable() {
    boolean b = disabled.getAndSet(false);
    if (b) {
      try {
        /* TODO */
        // doEnable();
      } catch (Exception e) {
        disabled.set(true);
        log.warn("Failed to enable link", e);
      }
    }
  }

  @Override
  public long getDataInCount() {
    return inPacketCount.get();
  }

  @Override
  public long getDataOutCount() {
    return outPacketCount.get();
  }

  @Override
  public Status getLinkStatus() {
    if (isDisabled()) {
      return Status.DISABLED;
    }
    if (state() == State.FAILED) {
      return Status.FAILED;
    }

    return connectionStatus();
  }

  @Override
  public boolean isDisabled() {
    return disabled.get();
  }

  protected Status connectionStatus() {
    return Status.OK;
  }

  @Override
  public Spec getSpec() {
    // TODO Auto-generated method stub
    return super.getSpec();
  }

  @Override
  public void onTuple(Stream arg0, Tuple tuple) {
    if (isRunningAndEnabled()) {

      byte[] packet;
      try {
        packet = encodeMessage(tuple.getColumn(DATA_CNAME));

        // long recTime = tuple.getColumn(PreparedCommand.CNAME_GENTIME);
        if (packet == null) {
          throw new ConfigurationException("no column named '%s' in the tuple", DATA_CNAME);
        } else {
          outStream.emitTuple(
              new Tuple(gftdef, Arrays.asList(tuple.getColumn(RECTIME_CNAME), packet)));

          updateOutStats(packet.length);
        }
      } catch (IOException e) {
        // TODO Auto-generated catch block
        e.printStackTrace();
      }
    }
  }
}
