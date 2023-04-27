package com.windhoverlabs.yamcs.tctm;

import com.fazecast.jSerialComm.SerialPort;
import com.google.common.util.concurrent.RateLimiter;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.WritableByteChannel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
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
import org.yamcs.parameter.ParameterValue;
import org.yamcs.parameter.SystemParametersProducer;
import org.yamcs.parameter.SystemParametersService;
import org.yamcs.protobuf.Yamcs.Value.Type;
import org.yamcs.tctm.Link;
import org.yamcs.tctm.PacketInputStream;
import org.yamcs.tctm.PacketTooLongException;
import org.yamcs.time.SimulationTimeService;
import org.yamcs.time.TimeService;
import org.yamcs.utils.DataRateMeter;
import org.yamcs.utils.StringConverter;
import org.yamcs.utils.YObjectLoader;
import org.yamcs.xtce.Parameter;
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
public class SerialStreamInOutProvider extends AbstractYamcsService
    implements Link, StreamSubscriber, SystemParametersProducer, Runnable {
  protected String deviceName;
  protected int baudRate;
  protected int dataBits;
  protected String stopBits;
  protected String parity;
  protected String flowControl;
  protected long initialDelay;
  String packetInputStreamClassName;
  YConfiguration packetInputStreamArgs;
  PacketInputStream packetInputStream;
  SerialPort serialPort = null;
  OutputStream outputStream = null;

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
  protected AtomicLong inDataCount = new AtomicLong(0);
  protected AtomicLong outDataCount = new AtomicLong(0);
  protected boolean updateSimulationTime;
  DataRateMeter inPacketRateMeter = new DataRateMeter();
  DataRateMeter outPacketRateMeter = new DataRateMeter();
  DataRateMeter inDataRateMeter = new DataRateMeter();
  DataRateMeter outDataRateMeter = new DataRateMeter();
  protected PacketPreprocessor packetPreprocessor;
  protected boolean hasPreprocessor = false;
  protected Stream inStream;
  protected Stream outStream;

  private Parameter spLinkStatus;
  private Parameter spInPacketCount;
  private Parameter spOutPacketCount;
  private Parameter spInPacketRate;
  private Parameter spOutPacketRate;
  private Parameter spInDataRate;
  private Parameter spOutDataRate;
  private Parameter spInDataCount;
  private Parameter spOutDataCount;

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
  private int leftTrim;
  private int rightTrim;
  int maxLength;

  String packetPreprocessorClassName;
  Object packetPreprocessorArgs;

  /**
   * Creates a new UDP Frame Data Link
   *
   * @throws ConfigurationException if port is not defined in the configuration
   */
  public void init(String instance, String name, YConfiguration config) {
    log = new Log(getClass(), instance);
    log.setContext(name);
    log.info("Initializing");

    try {
      super.init(instance, name, config);
    } catch (InitException e1) {
      // TODO Auto-generated catch block
      e1.printStackTrace();
    }

    this.deviceName = config.getString("device", "/dev/ttyUSB0");
    this.baudRate = config.getInt("baudRate", 57600);
    this.initialDelay = config.getLong("initialDelay", -1);
    this.dataBits = config.getInt("dataBits", 8);
    this.stopBits = config.getString("stopBits", "1");
    this.parity = config.getString("parity", "NONE");
    this.flowControl = config.getString("flowControl", "NONE");

    if (!("NONE".equalsIgnoreCase(this.parity)
        || "EVEN".equalsIgnoreCase(this.parity)
        || "ODD".equalsIgnoreCase(this.parity)
        || "MARK".equalsIgnoreCase(this.parity)
        || "SPACE".equalsIgnoreCase(this.parity))) {
      throw new ConfigurationException("Invalid Parity (NONE, EVEN, ODD, MARK or SPACE)");
    }

    if (!("NONE".equalsIgnoreCase(this.flowControl)
        || "RTS_CTS".equalsIgnoreCase(this.flowControl)
        || "XON_XOFF".equalsIgnoreCase(this.flowControl))) {
      throw new ConfigurationException("Invalid Flow Control (NONE, RTS_CTS, or XON_XOFF)");
    }

    if (!(this.dataBits == 5 || this.dataBits == 6 || this.dataBits == 7 || this.dataBits == 8)) {
      throw new ConfigurationException("Invalid Data Bits (5, 6, 7, or 8)");
    }

    if (!("1".equalsIgnoreCase(this.stopBits)
        || "1.5".equalsIgnoreCase(this.stopBits)
        || "2".equalsIgnoreCase(this.stopBits))) {
      throw new ConfigurationException("Invalid Stop Bits (1, 1.5, or 2)");
    }

    if (config.containsKey("packetInputStreamClassName")) {
      this.packetInputStreamClassName = config.getString("packetInputStreamClassName");
      this.packetInputStreamArgs = config.getConfig("packetInputStreamArgs");
    } else {
      this.packetInputStreamClassName = CcsdsPacketInputStream.class.getName();
      this.packetInputStreamArgs = YConfiguration.emptyConfig();
    }

    this.linkName = name;
    this.config = config;
    leftTrim = config.getInt("leftTrim", 0);
    rightTrim = config.getInt("rightTrim", 0);
    maxLength = config.getInt("maxFrameLength", MAX_LENGTH);
    eventProducer = EventProducerFactory.getEventProducer(instance, name, 10000);
    this.timeService = YamcsServer.getTimeService(instance);
    String inStreamName = config.getString("in_stream");
    String outStreamName = config.getString("out_stream");

    YarchDatabaseInstance ydb = YarchDatabase.getInstance(instance);
    this.inStream = getStream(ydb, inStreamName);
    this.outStream = getStream(ydb, outStreamName);

    this.outStream.addSubscriber(this);

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

    SystemParametersService collector = SystemParametersService.getInstance(yamcsInstance);
    setupSystemParameters(collector);
    collector.registerProducer((SystemParametersProducer) this);
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
      new Thread(this).start();
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

    byte[] trimmedByteArray =
        Arrays.copyOfRange(byteArray, this.leftTrim, byteArray.length - this.rightTrim);

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
    log.info("Running");
    if (initialDelay > 0) {
      try {
        Thread.sleep(initialDelay);
        initialDelay = -1;
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      }
    }

    while (isRunningAndEnabled()) {
      TmPacket tmpkt = getNextPacket();
      if (tmpkt == null) {
        break;
      }
      processPacket(tmpkt);
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
    inDataCount.getAndAdd(packetSize);
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
    outDataCount.getAndAdd(packetSize);
  }

  /**
   * Called to retrieve the next packet. It blocks in reading on the multicast socket
   *
   * @return anything that looks as a valid packet, just the size is taken into account to decide if
   *     it's valid or not
   */
  public TmPacket getNextPacket() {
    TmPacket pkt = null;

    while (isRunningAndEnabled()) {
      try {
        openDevice();
        byte[] packet = packetInputStream.readPacket();

        // log.info("Got packet");
        // for(int i = 0; i < packet.length; ++i) {
        //  System.console().printf("%02x ", packet[i]);
        // }
        // System.console().printf("\n");
        updateInStats(packet.length);
        pkt = new TmPacket(timeService.getMissionTime(), packet);
        pkt.setEarthRceptionTime(timeService.getHresMissionTime());
        break;
      } catch (IOException e) {
        if (isRunningAndEnabled()) {
          log.info(
              "Cannot open or read serial device {}::{}:{}'. Retrying in 10s",
              deviceName,
              e.getMessage(),
              e.toString());
          try {
            Thread.sleep(10000);
          } catch (InterruptedException e1) {
            // TODO Auto-generated catch block
            e1.printStackTrace();
          }
        }
        try {
          serialPort.closePort();
        } catch (Exception e2) {
        }
        serialPort = null;
        for (int ii = 0; ii < 10; ii++) {
          if (!isRunningAndEnabled()) {
            break;
          }
          try {
            Thread.sleep(10);
          } catch (InterruptedException e1) {
            Thread.currentThread().interrupt();
            return null;
          }
        }
      } catch (PacketTooLongException e) {
        log.warn(e.toString());
        try {
          serialPort.closePort();
        } catch (Exception e2) {
        }
        serialPort = null;
      }
    }
    return pkt;
  }

  protected void openDevice() throws IOException {
    if (this.serialPort == null) {
      log.info("Opening device {}", deviceName);
      this.serialPort = SerialPort.getCommPort(this.deviceName);
      this.serialPort.openPort();
      this.serialPort.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING, 0, 0);

      switch (this.flowControl) {
        case "NONE":
          log.info("Set Flow Control to NONE");
          this.serialPort.setFlowControl(SerialPort.FLOW_CONTROL_DISABLED);
          break;

        case "RTS_CTS":
          log.info("Set Flow Control to RTS_CTS");
          this.serialPort.setFlowControl(
              SerialPort.FLOW_CONTROL_CTS_ENABLED | SerialPort.FLOW_CONTROL_RTS_ENABLED);
          break;

        case "XON_XOFF":
          log.info("Set Flow Control to XON_XOFF");
          this.serialPort.setFlowControl(SerialPort.FLOW_CONTROL_XONXOFF_IN_ENABLED);
          break;
      }

      switch (this.parity) {
        case "NONE":
          log.info("Set Parity to NONE");
          this.serialPort.setParity(SerialPort.NO_PARITY);
          break;

        case "ODD":
          log.info("Set Parity to ODD_PARITY");
          this.serialPort.setParity(SerialPort.ODD_PARITY);
          break;

        case "EVEN":
          log.info("Set Parity to EVEN_PARITY");
          this.serialPort.setParity(SerialPort.EVEN_PARITY);
          break;

        case "MARK":
          log.info("Set Parity to MARK_PARITY");
          this.serialPort.setParity(SerialPort.MARK_PARITY);
          break;

        case "SPACE":
          log.info("Set Parity to SPACE_PARITY");
          this.serialPort.setParity(SerialPort.SPACE_PARITY);
          break;
      }

      if (this.serialPort.setNumDataBits(this.dataBits) == false) {
        throw new IOException("Invalid dataBits");
      }
      ;

      switch (this.stopBits) {
        case "1":
          log.info("Set Stop Bits to 1");
          if (this.serialPort.setNumStopBits(SerialPort.ONE_STOP_BIT) == false) {
            throw new IOException("Invalid stopBits");
          }
          ;
          break;

        case "1.5":
          log.info("Set Stop Bits to 1.5");
          if (this.serialPort.setNumStopBits(SerialPort.ONE_POINT_FIVE_STOP_BITS) == false) {
            throw new IOException("Invalid stopBits");
          }
          ;
          break;

        case "2":
          log.info("Set Stop Bits to 2");
          if (this.serialPort.setNumStopBits(SerialPort.TWO_STOP_BITS) == false) {
            throw new IOException("Invalid stopBits");
          }
          ;
          break;

        default:
          log.error("Invalid stopBits");
          throw new IOException("Invalid stopBits");
      }

      if (this.serialPort.setBaudRate(this.baudRate) == false) {
        throw new IOException("Invalid baudRate");
      }
      ;

      log.info("Opened device {}", deviceName);
    }

    if (packetInputStream == null) {
      try {
        log.info("Loading PacketInputStream {}", packetInputStreamClassName);
        packetInputStream = YObjectLoader.loadObject(packetInputStreamClassName);
        log.info("PacketInputStream {} loaded", packetInputStreamClassName);
      } catch (ConfigurationException e) {
        log.error("Cannot instantiate the packetInput stream", e);
        throw e;
      }

      log.info("Initializing {}", packetInputStreamClassName);
      packetInputStream.init(this.serialPort.getInputStream(), packetInputStreamArgs);
      log.info("{} initialized", packetInputStreamClassName);
    }

    this.outputStream = this.serialPort.getOutputStream();
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
      long recTime = tuple.getColumn(PreparedCommand.CNAME_GENTIME);
      if (pktData == null) {
        throw new ConfigurationException("no column named '%s' in the tuple", DATA_CNAME);
      } else {
        int retries = 5;
        boolean sent = false;

        ByteBuffer bb = ByteBuffer.wrap(pktData);
        bb.rewind();
        String reason = null;
        while (!sent && (retries > 0)) {
          try {
            if (serialPort == null) {
              openDevice();
            }
            WritableByteChannel channel = Channels.newChannel(outputStream);
            byte[] commandBytes = bb.array();
            StringBuilder commandByteDetails = new StringBuilder();
            commandByteDetails.append("***************************");
            for (byte b : commandBytes) {
              commandByteDetails.append(String.format("0x%x ", b));
            }
            commandByteDetails.append("***************************");
            eventProducer.sendInfo(commandByteDetails.toString());
            eventProducer.sendInfo(
                "Sent Command:"
                    + StringConverter.arrayToHexString(commandBytes, 0, commandBytes.length, true));
            eventProducer.sendInfo("Sent Command Length:" + commandBytes.length);

            channel.write(bb);
            updateOutStats(pktData.length);
            sent = true;
          } catch (IOException e) {
            reason =
                String.format("Error writing to TC device to %s : %s", deviceName, e.getMessage());
            log.warn(reason);
            //        try {
            //          if (serialPort != null) {
            ////            serialPort.close();
            //          }
            ////          serialPort = null;
            //        } catch (IOException e1) {
            //          e1.printStackTrace();
            //        }
          }
          retries--;
          if (!sent && (retries > 0)) {
            try {
              log.warn("Command not sent, retrying in 2 seconds");
              Thread.sleep(2000);
            } catch (InterruptedException e) {
              log.warn("exception {} thrown when sleeping 2 sec", e.toString());
              Thread.currentThread().interrupt();
            }
          }
        }
      }
    }
  }

  @Override
  public void setupSystemParameters(SystemParametersService sysParamCollector) {
    spLinkStatus =
        sysParamCollector.createEnumeratedSystemParameter(
            linkName + "/status", Status.class, "The current status of this interface");

    spInPacketCount =
        sysParamCollector.createSystemParameter(
            linkName + "/packetInCount",
            Type.UINT64,
            "The total number of packets that have been received via this interface");

    spOutPacketCount =
        sysParamCollector.createSystemParameter(
            linkName + "/packetOutCount",
            Type.UINT64,
            "The total number of packets that have been transmitted via this interface");

    spInPacketRate =
        sysParamCollector.createSystemParameter(
            linkName + "/packetInRate",
            Type.UINT64,
            "The rate packets that have been received via this interface");

    spOutPacketRate =
        sysParamCollector.createSystemParameter(
            linkName + "/packetOutRate",
            Type.UINT64,
            "The rate packets that have been transmitted via this interface");

    spInDataCount =
        sysParamCollector.createSystemParameter(
            linkName + "/dataInCount",
            Type.UINT64,
            "The total number of bytes that have been received via this interface");

    spOutDataCount =
        sysParamCollector.createSystemParameter(
            linkName + "/dataOutCount",
            Type.UINT64,
            "The total number of bytes that have been transmitted via this interface");

    spInDataRate =
        sysParamCollector.createSystemParameter(
            linkName + "/dataInRate",
            Type.UINT64,
            "The bit rate of data that has been received via this interface");

    spOutDataRate =
        sysParamCollector.createSystemParameter(
            linkName + "/dataOutRate",
            Type.UINT64,
            "The bit rate of data that has been transmitted via this interface");
  }

  @Override
  public List<ParameterValue> getSystemParameters() {
    long time = timeService.getMissionTime();

    ArrayList<ParameterValue> list = new ArrayList<>();
    try {
      collectSystemParameters(time, list);
    } catch (Exception e) {
      log.error("Exception caught when collecting link system parameters", e);
    }
    return list;
  }

  /**
   * adds system parameters link status and data in/out to the list.
   *
   * <p>The inheriting classes should call super.collectSystemParameters and then add their own
   * parameters to the list
   *
   * @param time
   * @param list
   */
  protected void collectSystemParameters(long time, List<ParameterValue> list) {
    list.add(SystemParametersService.getPV(spLinkStatus, time, getLinkStatus()));
    list.add(SystemParametersService.getPV(spInPacketCount, time, inPacketCount.get()));
    list.add(SystemParametersService.getPV(spOutPacketCount, time, outPacketCount.get()));
    list.add(
        SystemParametersService.getPV(
            spInPacketRate, time, inPacketRateMeter.getFiveSecondsRate()));
    list.add(
        SystemParametersService.getPV(
            spOutPacketRate, time, outPacketRateMeter.getFiveSecondsRate()));
    list.add(
        SystemParametersService.getPV(spInDataRate, time, inDataRateMeter.getFiveSecondsRate()));
    list.add(
        SystemParametersService.getPV(spOutDataRate, time, outDataRateMeter.getFiveSecondsRate()));
    list.add(SystemParametersService.getPV(spInDataCount, time, inDataCount.get()));
    list.add(SystemParametersService.getPV(spOutDataCount, time, outDataCount.get()));
  }
}
