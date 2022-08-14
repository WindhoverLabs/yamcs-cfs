package com.windhoverlabs.yamcs.tctm.ccsds;

import static org.yamcs.xtce.NameDescription.qualifiedName;
import static org.yamcs.xtce.XtceDb.YAMCS_SPACESYSTEM_NAME;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.openmuc.jrxtx.SerialPort;
import org.openmuc.jrxtx.SerialPortBuilder;
import org.yamcs.ConfigurationException;
import org.yamcs.YConfiguration;
import org.yamcs.YamcsServer;
import org.yamcs.cmdhistory.CommandHistoryPublisher;
import org.yamcs.commanding.PreparedCommand;
import org.yamcs.parameter.AggregateValue;
import org.yamcs.parameter.ParameterValue;
import org.yamcs.parameter.SystemParametersService;
import org.yamcs.protobuf.Yamcs.Value.Type;
import org.yamcs.tctm.AbstractLink;
import org.yamcs.tctm.AggregatedDataLink;
import org.yamcs.tctm.Link;
import org.yamcs.tctm.TcDataLink;
import org.yamcs.utils.ValueUtility;
import org.yamcs.xtce.AggregateParameterType;
import org.yamcs.xtce.IntegerParameterType;
import org.yamcs.xtce.Member;
import org.yamcs.xtce.NameDescription;
import org.yamcs.xtce.Parameter;
import org.yamcs.xtce.StringParameterType;
import org.yamcs.xtce.SystemParameter;
import org.yamcs.xtce.UnitType;
import org.yamcs.xtce.XtceDb;

/**
 * A link that uses one serial port for sending commands and receiving telemetry. It aggregates the
 * SerialTmFrameLink and SerialTcFrameLink links.
 *
 * @author lgomez
 */
public class SerialTmTcFrameLink extends AbstractLink
    implements Runnable, TcDataLink, AggregatedDataLink {

  protected String deviceName;
  protected String syncSymbol;
  protected int baudRate;
  protected int dataBits;
  protected String stopBits;
  protected String parity;
  protected String flowControl;
  protected long initialDelay;
  private SerialPort serialPort = null;

  SerialTmFrameLink TmLink = null;
  SerialTcFrameLink TcLink = null;

  Thread thread;
  private XtceDb mdb;
  private SystemParameter spDeviceName;
  private AggregateParameterType spDeviceHKType; // Housekeeping info for the serial device
  private Parameter deviceHKParam;

  private AggregateParameterType
      spPacketInputStreamHKType; // Housekeeping info for the SerialTmFrameLink link
  private Parameter SerialTmFrameLinkHKParam;

  private ArrayList<Integer> last10FramesCounts = new ArrayList<Integer>();

  @Override
  public void init(String instance, String name, YConfiguration config)
      throws ConfigurationException {
    super.init(instance, name, config);

    this.deviceName = config.getString("device", "/dev/ttyUSB0");
    this.syncSymbol = config.getString("syncSymbol", "");
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

    try {
      openDevice();
    } catch (IOException e) {
      // TODO Auto-generated catch block
      log.warn("Failed to open serial port.");
    }

    TcLink = new SerialTcFrameLink();
    TmLink = new SerialTmFrameLink();

    TcLink.init(instance, name, config.getConfig("tc_config"));

    TmLink.setSerialPort(serialPort);
    TcLink.setSerialPort(serialPort);

    TmLink.init(instance, name, config.getConfig("tm_config"));
  }

  public void openDevice() throws IOException {

    if (serialPort == null) {
      serialPort = SerialPortBuilder.newBuilder(deviceName).setBaudRate(baudRate).build();

      switch (this.flowControl) {
        case "NONE":
          serialPort.setFlowControl(org.openmuc.jrxtx.FlowControl.NONE);
          break;

        case "RTS_CTS":
          serialPort.setFlowControl(org.openmuc.jrxtx.FlowControl.RTS_CTS);
          break;

        case "XON_XOFF":
          serialPort.setFlowControl(org.openmuc.jrxtx.FlowControl.XON_XOFF);
          break;
      }

      switch (this.parity) {
        case "NONE":
          serialPort.setParity(org.openmuc.jrxtx.Parity.NONE);
          break;

        case "ODD":
          serialPort.setParity(org.openmuc.jrxtx.Parity.ODD);
          break;

        case "EVEN":
          serialPort.setParity(org.openmuc.jrxtx.Parity.EVEN);
          break;

        case "MARK":
          serialPort.setParity(org.openmuc.jrxtx.Parity.MARK);
          break;

        case "SPACE":
          serialPort.setParity(org.openmuc.jrxtx.Parity.SPACE);
          break;
      }

      switch (this.dataBits) {
        case 5:
          serialPort.setDataBits(org.openmuc.jrxtx.DataBits.DATABITS_5);
          break;

        case 6:
          serialPort.setDataBits(org.openmuc.jrxtx.DataBits.DATABITS_6);
          break;

        case 7:
          serialPort.setDataBits(org.openmuc.jrxtx.DataBits.DATABITS_7);
          break;

        case 8:
          serialPort.setDataBits(org.openmuc.jrxtx.DataBits.DATABITS_8);
          break;
      }

      switch (this.stopBits) {
        case "1":
          serialPort.setStopBits(org.openmuc.jrxtx.StopBits.STOPBITS_1);
          break;

        case "1.5":
          serialPort.setStopBits(org.openmuc.jrxtx.StopBits.STOPBITS_1_5);
          break;

        case "2":
          serialPort.setStopBits(org.openmuc.jrxtx.StopBits.STOPBITS_2);
          break;
      }
    }
  }

  @Override
  protected void doEnable() throws Exception {
    if (serialPort == null) {
      openDevice();
      log.info("Listening on {}", deviceName);
    }
    TcLink.setSerialPort(serialPort);
    TmLink.setSerialPort(serialPort);
    TmLink.doEnable();
    TcLink.doEnable();

    thread = new Thread(this);
    thread.start();
  }

  @Override
  protected void doDisable() throws Exception {

    if (serialPort != null) {
      try {
        log.info("Closing {}", deviceName);
        serialPort.close();
      } catch (IOException e) {
        log.warn("Exception raised closing the serial port:", e);
      }
      serialPort = null;
    }
    TmLink.disable();
    TcLink.disable();
  }

  @Override
  public void doStart() {
    TmLink.startAsync();
    TcLink.startAsync();
    if (!isDisabled()) {
      new Thread(this).start();
    }
    notifyStarted();
  }

  @Override
  public void doStop() {
    try {
      serialPort.close();
    } catch (IOException e) {
      // TODO Auto-generated catch block
      e.printStackTrace();
    }
    TmLink.doStop();
    TcLink.doStop();

    notifyStopped();
  }

  @Override
  public long getDataInCount() {
    // TODO Auto-generated method stub
    return TmLink.getDataInCount();
  }

  @Override
  public long getDataOutCount() {
    // TODO Auto-generated method stub
    return TcLink.getDataOutCount() + TmLink.getDataOutCount();
  }

  @Override
  public void resetCounters() {
    TcLink.resetCounters();
    TmLink.resetCounters();
  }

  @Override
  public void run() {
    while (isRunningAndEnabled())
      ;
  }

  @Override
  protected Status connectionStatus() {
    return (serialPort == null) ? Status.DISABLED : Status.OK;
  }

  @Override
  public List<Link> getSubLinks() {
    List<Link> subLinks = new ArrayList<Link>();
    subLinks.add(TcLink);
    subLinks.add(TmLink);

    return subLinks;
  }

  @Override
  public void setCommandHistoryPublisher(CommandHistoryPublisher commandHistoryListener) {
    TcLink.setCommandHistoryPublisher(commandHistoryListener);
  }

  @Override
  public void sendTc(PreparedCommand preparedCommand) {
    // Log warning. This should not be called.
    log.warn("sendTc");
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
    super.collectSystemParameters(time, list);
    list.add(SystemParametersService.getPV(spDeviceName, time, deviceName));
  }

  @Override
  public Collection<ParameterValue> getSystemParameters(long gentime) {
    List<ParameterValue> pvlist = new ArrayList<>();

    AggregateValue deviceAggregateV = new AggregateValue(spDeviceHKType.getMemberNames());
    deviceAggregateV.setMemberValue("name", ValueUtility.getStringValue(deviceName));
    deviceAggregateV.setMemberValue(
        "open", ValueUtility.getStringValue(String.valueOf(!serialPort.isClosed())));

    ParameterValue devicePV = new ParameterValue(deviceHKParam);
    devicePV.setGenerationTime(gentime);
    devicePV.setEngValue(deviceAggregateV);

    AggregateValue serialTmFrameLinkAggregateV =
        new AggregateValue(spPacketInputStreamHKType.getMemberNames());

    serialTmFrameLinkAggregateV.setMemberValue(
        "outOfSyncByteCount",
        ValueUtility.getUint32Value(
            ((SdlpPacketInputStream) TmLink.getPacketInputStream()).getOutOfSyncByteCount()));
    serialTmFrameLinkAggregateV.setMemberValue(
        "inSyncByteCount",
        ValueUtility.getUint32Value(
            ((SdlpPacketInputStream) TmLink.getPacketInputStream()).getInSyncByteCount()));
    serialTmFrameLinkAggregateV.setMemberValue(
        "asmCursor",
        ValueUtility.getUint32Value(
            ((SdlpPacketInputStream) TmLink.getPacketInputStream()).getAsmCursor()));
    serialTmFrameLinkAggregateV.setMemberValue(
        "parserState",
        ValueUtility.getStringValue(
            ((SdlpPacketInputStream) TmLink.getPacketInputStream()).getParserState().toString()));
    serialTmFrameLinkAggregateV.setMemberValue(
        "caduLength",
        ValueUtility.getUint32Value(
            ((SdlpPacketInputStream) TmLink.getPacketInputStream()).getCaduLength()));
    serialTmFrameLinkAggregateV.setMemberValue(
        "fatFrameBytes",
        ValueUtility.getUint32Value(
            ((SdlpPacketInputStream) TmLink.getPacketInputStream()).getFatFrameBytes()));
    serialTmFrameLinkAggregateV.setMemberValue(
        "fixedLength",
        ValueUtility.getUint32Value(
            ((SdlpPacketInputStream) TmLink.getPacketInputStream()).getFixedLength()));
    serialTmFrameLinkAggregateV.setMemberValue(
        "fatFrameCount",
        ValueUtility.getUint32Value(
            ((SdlpPacketInputStream) TmLink.getPacketInputStream()).getFatFrameCount()));
    serialTmFrameLinkAggregateV.setMemberValue(
        "packet",
        ValueUtility.getStringValue(
            ((SdlpPacketInputStream) TmLink.getPacketInputStream()).getPacket()));
    serialTmFrameLinkAggregateV.setMemberValue(
        "rcvdCaduCount",
        ValueUtility.getUint32Value(
            ((SdlpPacketInputStream) TmLink.getPacketInputStream()).getRcvdCaduCount()));

    ParameterValue serialTmFrameLinkPV = new ParameterValue(SerialTmFrameLinkHKParam);

    devicePV.setGenerationTime(gentime);
    devicePV.setEngValue(deviceAggregateV);

    serialTmFrameLinkPV.setGenerationTime(gentime);
    serialTmFrameLinkPV.setEngValue(serialTmFrameLinkAggregateV);

    pvlist.add(devicePV);
    pvlist.add(serialTmFrameLinkPV);
    pvlist.addAll(TmLink.getSystemParameters(gentime));
    pvlist.addAll(TcLink.getSystemParameters(gentime));

    return pvlist;
  }

  /**
   * Adds HK messages to the downlink that are helpful for understanding the state of this link at
   * runtime.
   */
  @Override
  public void setupSystemParameters(SystemParametersService sysParamCollector) {
    super.setupSystemParameters(sysParamCollector);
    TmLink.setupSystemParameters(sysParamCollector);
    TcLink.setupSystemParameters(sysParamCollector);
    mdb = YamcsServer.getServer().getInstance(yamcsInstance).getXtceDb();

    IntegerParameterType intType =
        (IntegerParameterType) sysParamCollector.getBasicType(Type.UINT64);
    List<UnitType> unitSet = new ArrayList<>();
    unitSet.add(new UnitType(""));
    intType.setUnitSet(unitSet);
    StringParameterType stringType =
        (StringParameterType) sysParamCollector.getBasicType(Type.STRING);
    spDeviceName =
        mdb.createSystemParameter(
            qualifiedName(YAMCS_SPACESYSTEM_NAME, linkName + "/deviceName"),
            stringType,
            "The name of the serial port device.");

    spDeviceHKType =
        new AggregateParameterType.Builder()
            .setName("DeviceHK")
            .addMember(new Member("name", sysParamCollector.getBasicType(Type.STRING)))
            .addMember(new Member("open", sysParamCollector.getBasicType(Type.STRING)))
            .build();
    deviceHKParam =
        mdb.createSystemParameter(
            qualifiedName(YAMCS_SPACESYSTEM_NAME, linkName + "/SerialTmTcFrameLink"),
            spDeviceHKType,
            "Housekeeping information. Status of the device, name, etc");

    // TODO Fix this
    // Extract the last token of the class name, since it will be in the form of
    // PackageA.PackageB.ClassName

    String[] classNameParts = TmLink.getPacketInputStream().getClass().getName().split("\\.");
    // classNameParts[classNameParts.length-1];
    String packInputStreamClassName = classNameParts[classNameParts.length - 1];

    spPacketInputStreamHKType =
        new AggregateParameterType.Builder()
            .setName(packInputStreamClassName)
            .addMember(
                new Member("outOfSyncByteCount", sysParamCollector.getBasicType(Type.UINT32)))
            .addMember(new Member("inSyncByteCount", sysParamCollector.getBasicType(Type.UINT32)))
            .addMember(new Member("asmCursor", sysParamCollector.getBasicType(Type.UINT32)))
            .addMember(new Member("parserState", sysParamCollector.getBasicType(Type.STRING)))
            .addMember(new Member("caduLength", sysParamCollector.getBasicType(Type.UINT32)))
            .addMember(new Member("fatFrameBytes", sysParamCollector.getBasicType(Type.UINT32)))
            .addMember(new Member("fixedLength", sysParamCollector.getBasicType(Type.UINT32)))
            .addMember(new Member("fatFrameCount", sysParamCollector.getBasicType(Type.UINT32)))
            .addMember(new Member("packet", sysParamCollector.getBasicType(Type.STRING)))
            .addMember(new Member("rcvdCaduCount", sysParamCollector.getBasicType(Type.UINT32)))
            .build();

    SerialTmFrameLinkHKParam =
        mdb.createSystemParameter(
            qualifiedName(
                YAMCS_SPACESYSTEM_NAME,
                linkName
                    + "/SerialTmTcFrameLink"
                    + NameDescription.PATH_SEPARATOR
                    + packInputStreamClassName
                    + "_HK"),
            spPacketInputStreamHKType,
            "Housekeeping information. Status of SerialTmFrameLink.");
  }

  //    TODO: Could be helpful in the future to show users frame rate on the downlinnk. Not high
  // priority at the
  //    moment however.
  //    public void updateFrameCountList(int newFrameCount) {
  //        if (last10FramesCounts.size() < 10) {
  //            last10FramesCounts.add(newFrameCount);
  //        } else {
  //
  //        }
  //    }
  //
  //    private int calculateFrameRate() {
  //        return 0;
  //    }
}
