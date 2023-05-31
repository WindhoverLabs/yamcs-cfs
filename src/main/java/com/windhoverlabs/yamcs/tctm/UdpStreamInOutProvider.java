package com.windhoverlabs.yamcs.tctm;

import com.google.common.util.concurrent.RateLimiter;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
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
import org.yamcs.commanding.PreparedCommand;
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
public class UdpStreamInOutProvider extends AbstractYamcsService
    implements Link, StreamSubscriber, SystemParametersProducer, Runnable {
  String host;
  DatagramSocket socket;
  InetAddress address;
  Thread thread;
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
  protected boolean hasPreprocessor = false;
  protected Stream inStream;
  protected Stream outStream;

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

  private DatagramSocket inSocket;
  private DatagramSocket outSocket;
  private int inPort;
  private int outPort;
  private int offset;
  private int rightTrim;
  int maxLength;
  //  private volatile int invalidDatagramCount = 0;

  DatagramPacket datagram;
  String packetPreprocessorClassName;
  Object packetPreprocessorArgs;

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
    host = config.getString("host");
    this.linkName = name;
    this.config = config;
    this.inPort = config.getInt("in_port");
    this.outPort = config.getInt("out_port");
    offset = config.getInt("offset", 0);
    rightTrim = config.getInt("rightTrim", 0);
    maxLength = config.getInt("maxLength", MAX_LENGTH);
    log = new Log(getClass(), instance);
    log.setContext(name);
    eventProducer = EventProducerFactory.getEventProducer(instance, name, 10000);
    this.timeService = YamcsServer.getTimeService(instance);
    String inStreamName = config.getString("in_stream");
    String outStreamName = config.getString("out_stream");

    YarchDatabaseInstance ydb = YarchDatabase.getInstance(instance);
    this.inStream = getStream(ydb, inStreamName);
    this.outStream = getStream(ydb, outStreamName);

    this.outStream.addSubscriber(this);

    try {
      address = InetAddress.getByName(host);
    } catch (UnknownHostException e) {
      throw new ConfigurationException("Cannot resolve host '" + host + "'", e);
    }
    if (config.containsKey("frameMaxRate")) {
      outRateLimiter = RateLimiter.create(config.getDouble("frameMaxRate"), 1, TimeUnit.SECONDS);
    }

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
  public void resetCounters() {
    inPacketCount.set(0);
    outPacketCount.set(0);
  }

  @Override
  public void doStart() {
    if (!isDisabled()) {
      try {
        inSocket = new DatagramSocket(inPort);
        outSocket = new DatagramSocket();
        new Thread(this).start();
      } catch (SocketException e) {
        log.error("Socket exception", e);
        notifyFailed(e);
      }
    }
    notifyStarted();
  }

  @Override
  public void doStop() {
    inSocket.close();
    outSocket.close();
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
    
    inStream.emitTuple(new Tuple(gftdef, Arrays.asList(rectime, trimmedByteArray)));

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
        inSocket.receive(datagram);
        updateInStats(datagram.getLength());
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
        log.warn("exception thrown when reading from the UDP socket at port {}", inPort, e);
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
      byte[] pktData = tuple.getColumn(DATA_CNAME);
      long recTime = tuple.getColumn(RECTIME_CNAME);
      if (pktData == null) {
        throw new ConfigurationException("no column named '%s' in the tuple", DATA_CNAME);
      } else {
        DatagramPacket dtg = new DatagramPacket(pktData, pktData.length, address, outPort);
        try {
          outSocket.send(dtg);
          updateOutStats(pktData.length);
        } catch (IOException e) {
          log.warn("Error sending datagram", e);
          notifyFailed(e);
          return;
        }
      }
    }
  }
}
