package com.windhoverlabs.yamcs.tctm;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.yamcs.AbstractYamcsService;
import org.yamcs.ConfigurationException;
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
import org.yamcs.utils.YObjectLoader;
import org.yamcs.yarch.ColumnDefinition;
import org.yamcs.yarch.DataType;
import org.yamcs.yarch.Stream;
import org.yamcs.yarch.Tuple;
import org.yamcs.yarch.TupleDefinition;
import org.yamcs.yarch.YarchDatabase;
import org.yamcs.yarch.YarchDatabaseInstance;

/**
 * Receives telemetry fames via UDP. One UDP datagram = one TM frame.
 *
 * @author nm
 */
public class UdpStreamInProvider extends AbstractYamcsService
    implements Link, SystemParametersProducer, Runnable {
  protected YConfiguration config;
  protected String linkName;
  protected AtomicBoolean disabled = new AtomicBoolean(false);
  protected Log log;
  protected EventProducer eventProducer;
  protected TimeService timeService;
  protected AtomicLong packetCount = new AtomicLong(0);
  protected boolean updateSimulationTime;
  DataRateMeter packetRateMeter = new DataRateMeter();
  DataRateMeter dataRateMeter = new DataRateMeter();
  protected org.yamcs.tctm.PacketPreprocessor packetPreprocessor;
  protected boolean hasPreprocessor = false;
  protected Stream stream;

  static TupleDefinition gftdef;

  static final String RECTIME_CNAME = "rectime";
  static final String DATA_CNAME = "data";

  static {
    gftdef = new TupleDefinition();
    gftdef.addColumn(new ColumnDefinition(RECTIME_CNAME, DataType.TIMESTAMP));
    gftdef.addColumn(new ColumnDefinition(DATA_CNAME, DataType.BINARY));
  }

  static final String CFG_PREPRO_CLASS = "packetPreprocessorClassName";

  static final int MAX_LENGTH = 1500;

  private DatagramSocket tmSocket;
  private int port;
  private int offset;
  private int rightTrim;
  private int rcvBufferSize;
  int maxLength;
  //  private volatile int invalidDatagramCount = 0;

  DatagramPacket datagram;
  String packetPreprocessorClassName;
  Object packetPreprocessorArgs;
  Thread thread;

  /**
   * Creates a new UDP Frame Data Link
   *
   * @throws ConfigurationException if port is not defined in the configuration
   */
  public void init(String instance, String name, YConfiguration config)
      throws ConfigurationException {
    // super.init(instance, name, config);
    this.config = config;
    this.linkName = name;
    port = config.getInt("port");
    offset = config.getInt("offset", 0);
    rcvBufferSize = config.getInt("rcvBufferSize", 0);
    rightTrim = config.getInt("rightTrim", 0);
    maxLength = config.getInt("maxLength", MAX_LENGTH);
    log = new Log(getClass(), instance);
    log.setContext(name);
    eventProducer = EventProducerFactory.getEventProducer(instance, name, 10000);
    this.timeService = YamcsServer.getTimeService(instance);
    String streamName = config.getString("stream");

    YarchDatabaseInstance ydb = YarchDatabase.getInstance(instance);
    this.stream = getStream(ydb, streamName);

    if (config.containsKey(CFG_PREPRO_CLASS)) {
      this.hasPreprocessor = true;

      this.packetPreprocessorClassName = config.getString(CFG_PREPRO_CLASS);

      if (config.containsKey("packetPreprocessorArgs")) {
        this.packetPreprocessorArgs = config.getConfig("packetPreprocessorArgs");
      }

      try {
        if (packetPreprocessorArgs != null) {
          packetPreprocessor =
              YObjectLoader.loadObject(
                  packetPreprocessorClassName, instance, packetPreprocessorArgs);
        } else {
          packetPreprocessor = YObjectLoader.loadObject(packetPreprocessorClassName, instance);
        }
      } catch (ConfigurationException e) {
        log.error("Cannot instantiate the packet preprocessor", e);
        throw e;
      }
    }

    datagram = new DatagramPacket(new byte[maxLength], maxLength);

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
  public void resetCounters() {}

  @Override
  public void doStart() {
    if (!isDisabled()) {
      try {
        tmSocket = new DatagramSocket(port);
        if (rcvBufferSize > 0) {
          tmSocket.setReceiveBufferSize(rcvBufferSize);
        }
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

    byte[] trimmedByteArray =
        Arrays.copyOfRange(byteArray, this.offset, byteArray.length - this.rightTrim);

    stream.emitTuple(new Tuple(gftdef, Arrays.asList(rectime, trimmedByteArray)));

    if (updateSimulationTime) {
      SimulationTimeService sts = (SimulationTimeService) timeService;
      if (!tmpkt.isInvalid()) {
        sts.setSimElapsedTime(tmpkt.getGenerationTime());
      }
    }
  }

  @Override
  public void run() {
    while (isRunningAndEnabled()) {
      TmPacket tmpkt = getNextPacket();
      if (tmpkt != null) {
        processPacket(tmpkt);
      }
    }
  }

  /**
   * called when a new packet is received to update the statistics
   *
   * @param packetSize
   */
  protected void updateStats(int packetSize) {
    packetCount.getAndIncrement();
    packetRateMeter.mark(1);
    dataRateMeter.mark(packetSize);
  }

  /**
   * Called to retrieve the next packet. It blocks in reading on the multicast socket
   *
   * @return anything that looks as a valid packet, just the size is taken into account to decide if
   *     it's valid or not
   */
  public TmPacket getNextPacket() {
    ByteBuffer packet = null;

    while (isRunning()) {
      try {
        tmSocket.receive(datagram);
        updateStats(datagram.getLength());
        packet = ByteBuffer.allocate(datagram.getLength());
        packet.put(datagram.getData(), datagram.getOffset(), datagram.getLength());
        break;
      } catch (IOException e) {
        if (!isRunning()
            || isDisabled()) { // the shutdown or disable will close the socket and that will
          // generate an exception
          // which we ignore here
          return null;
        }
        log.warn("exception thrown when reading from the UDP socket at port {}", port, e);
      }
    }

    if (packet != null) {
      TmPacket tmPacket = new TmPacket(timeService.getMissionTime(), packet.array());
      tmPacket.setEarthRceptionTime(timeService.getHresMissionTime());
      if (this.hasPreprocessor) {
        return packetPreprocessor.process(tmPacket);
      } else {
        return tmPacket;
      }
    } else {
      return null;
    }
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
    return packetCount.get();
  }

  @Override
  public long getDataOutCount() {
    return 0;
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
}
