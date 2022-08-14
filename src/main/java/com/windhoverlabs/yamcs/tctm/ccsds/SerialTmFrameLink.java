package com.windhoverlabs.yamcs.tctm.ccsds;

import static org.yamcs.xtce.NameDescription.qualifiedName;
import static org.yamcs.xtce.XtceDb.YAMCS_SPACESYSTEM_NAME;

import java.io.IOException;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.openmuc.jrxtx.SerialPort;
import org.openmuc.jrxtx.SerialPortBuilder;
import org.yamcs.ConfigurationException;
import org.yamcs.YConfiguration;
import org.yamcs.YamcsServer;
import org.yamcs.parameter.AggregateValue;
import org.yamcs.parameter.ParameterValue;
import org.yamcs.parameter.SystemParametersService;
import org.yamcs.protobuf.Yamcs.Value.Type;
import org.yamcs.tctm.CcsdsPacketInputStream;
import org.yamcs.tctm.PacketInputStream;
import org.yamcs.tctm.TcTmException;
import org.yamcs.tctm.ccsds.AbstractTmFrameLink;
import org.yamcs.utils.StringConverter;
import org.yamcs.utils.ValueUtility;
import org.yamcs.utils.YObjectLoader;
import org.yamcs.xtce.AggregateParameterType;
import org.yamcs.xtce.IntegerParameterType;
import org.yamcs.xtce.Member;
import org.yamcs.xtce.NameDescription;
import org.yamcs.xtce.Parameter;
import org.yamcs.xtce.ParameterType;
import org.yamcs.xtce.StringParameterType;
import org.yamcs.xtce.UnitType;
import org.yamcs.xtce.XtceDb;

/**
 * Receives telemetry fames via serial interface.
 *
 * @author Mathew Benson (mbenson@windhoverlabs.com)
 */
public class SerialTmFrameLink extends AbstractTmFrameLink implements Runnable {
  protected String deviceName;
  protected String syncSymbol;
  protected int baudRate;
  protected int dataBits;
  protected String stopBits;
  protected String parity;
  protected String flowControl;
  protected long initialDelay;

  SerialPort serialPort = null;
  String packetInputStreamClassName;
  YConfiguration packetInputStreamArgs;
  PacketInputStream packetInputStream;

  Thread thread;
  private XtceDb mdb;
  private Parameter SerialTmFrameLinkHKParam;
  private AggregateParameterType spSerialTmFrameLinkHKType;
  private ParameterType spPacketInputStreamHKType;

  /**
   * Creates a new Serial Frame Data Link
   *
   * @throws ConfigurationException if port is not defined in the configuration
   */
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

    if (!(   "NONE".equalsIgnoreCase(this.parity)
          || "EVEN".equalsIgnoreCase(this.parity)
          || "ODD".equalsIgnoreCase(this.parity)
          || "MARK".equalsIgnoreCase(this.parity)
          || "SPACE".equalsIgnoreCase(this.parity))) {
      throw new ConfigurationException("Invalid Parity (NONE, EVEN, ODD, MARK or SPACE)");
    }

    if (!(   "NONE".equalsIgnoreCase(this.flowControl)
          || "RTS_CTS".equalsIgnoreCase(this.flowControl)
          || "XON_XOFF".equalsIgnoreCase(this.flowControl))) {
      throw new ConfigurationException("Invalid Flow Control (NONE, RTS_CTS, or XON_XOFF)");
    }

    if (!(   this.dataBits == 5 
          || this.dataBits == 6 
          || this.dataBits == 7 
          || this.dataBits == 8)) 
    {
      throw new ConfigurationException("Invalid Data Bits (5, 6, 7, or 8)");
    }

    if (!(   "1".equalsIgnoreCase(this.stopBits)
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

    try {
      packetInputStream = YObjectLoader.loadObject(packetInputStreamClassName);
    } catch (ConfigurationException e) {
      log.error("Cannot instantiate the packetInput stream", e);
      throw e;
    }
    try {
      packetInputStream.init(serialPort.getInputStream(), packetInputStreamArgs);
    } catch (IOException e) {
      // TODO Auto-generated catch block
      e.printStackTrace();
    }
  }

  @Override
  public void doStart() {
    if (!isDisabled()) {
      if (serialPort == null) {
        openDevice();
        log.info("Listening on {}", deviceName);
      }
      new Thread(this).start();
    }
    notifyStarted();
  }

  @Override
  public void doStop() {
    if (serialPort != null) {
      try {
        log.info("Closing {}", deviceName);
        serialPort.close();
      } catch (IOException e) {
        log.warn("Exception raised when closing the serial port:", e);
      }
      serialPort = null;
    }
    notifyStopped();
  }

  @Override
  public void run() {
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
      try {

        byte[] packet = packetInputStream.readPacket();

        int length = packet.length;
        if (log.isTraceEnabled()) {
          log.trace(
              "Received packet of length {}: {}",
              length,
              StringConverter.arrayToHexString(packet, 0, length, true));
        }
        if (length < frameHandler.getMinFrameSize()) {
          eventProducer.sendWarning(
              "Error processing frame: size "
                  + length
                  + " shorter than minimum allowed "
                  + frameHandler.getMinFrameSize());
          continue;
        }
        if (length > frameHandler.getMaxFrameSize()) {
          eventProducer.sendWarning(
              "Error processing frame: size "
                  + length
                  + " longer than maximum allowed "
                  + frameHandler.getMaxFrameSize());
          continue;
        }

        frameCount.getAndIncrement();

        frameHandler.handleFrame(timeService.getHresMissionTime(), packet, 0, length);
      } catch (TcTmException e) {
        eventProducer.sendWarning("Error processing frame: " + e.toString());
      } catch (Exception e) {
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
          "OK (%s) %nValid datagrams received: %d%n", deviceName, frameCount.get());
    }
  }

  @Override
  protected void doDisable() {
    if (serialPort != null) {
      try {
        log.info("Closing {}", deviceName);
        serialPort.close();
      } catch (IOException e) {
        log.warn("Exception raised closing the serial port:", e);
      }
      serialPort = null;
    }
  }

  @Override
  protected void doEnable() throws SocketException {
    if (serialPort == null) {
      openDevice();
      log.info("Listening on {}", deviceName);
    }
    new Thread(this).start();
  }

  @Override
  protected Status connectionStatus() {
    return (serialPort == null) ? Status.DISABLED : Status.OK;
  }

  protected void openDevice() {
    try {
      if (serialPort == null) {
        serialPort = SerialPortBuilder.newBuilder(deviceName).setBaudRate(baudRate).build();
      }
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

      try {
        packetInputStream = YObjectLoader.loadObject(packetInputStreamClassName);
      } catch (ConfigurationException e) {
        log.error("Cannot instantiate the packetInput stream", e);
        throw e;
      }
      packetInputStream.init(serialPort.getInputStream(), packetInputStreamArgs);
    } catch (IOException e) {
      if (isRunningAndEnabled()) {
        log.info(
            "Cannot open or read serial device {}::{}:{}'. Retrying in 10s",
            deviceName,
            e.getMessage(),
            e.toString());
      }
      try {
        serialPort.close();
      } catch (Exception e2) {
      }
      serialPort = null;
      for (int i = 0; i < 10; i++) {
        if (!isRunningAndEnabled()) {
          break;
        }
        try {
          Thread.sleep(10);
        } catch (InterruptedException e1) {
          Thread.currentThread().interrupt();
          return;
        }
      }
    }
  }

  public void setSerialPort(SerialPort newSerialPort) {
    serialPort = newSerialPort;
  }

  public PacketInputStream getPacketInputStream() {
    return packetInputStream;
  }

  @Override
  public Collection<ParameterValue> getSystemParameters(long gentime) {
    List<ParameterValue> pvlist = new ArrayList<>();

    super.getSystemParameters(gentime);

    AggregateValue serialTmFrameLinkAggregateV =
        new AggregateValue(spSerialTmFrameLinkHKType.getMemberNames());

    serialTmFrameLinkAggregateV.setMemberValue(
        "Mission_Time", ValueUtility.getTimestampValue(getCurrentTime()));

    serialTmFrameLinkAggregateV.setMemberValue(
        "Time", ValueUtility.getTimestampValue(timeService.getHresMissionTime().getMillis()));

    ParameterValue serialTmFrameLinkPV = new ParameterValue(SerialTmFrameLinkHKParam);
    serialTmFrameLinkPV.setGenerationTime(gentime);
    serialTmFrameLinkPV.setEngValue(serialTmFrameLinkAggregateV);

    pvlist.add(serialTmFrameLinkPV);

    return pvlist;
  }

  /**
   * Adds HK messages to the downlink that are helpful for understanding the state of this link at
   * runtime.
   */
  @Override
  public void setupSystemParameters(SystemParametersService sysParamCollector) {
    super.setupSystemParameters(sysParamCollector);
    mdb = YamcsServer.getServer().getInstance(yamcsInstance).getXtceDb();

    IntegerParameterType intType =
        (IntegerParameterType) sysParamCollector.getBasicType(Type.UINT64);
    List<UnitType> unitSet = new ArrayList<>();
    unitSet.add(new UnitType(""));
    intType.setUnitSet(unitSet);
    StringParameterType stringType =
        (StringParameterType) sysParamCollector.getBasicType(Type.STRING);
    // spDeviceName = mdb.createSystemParameter(qualifiedName(YAMCS_SPACESYSTEM_NAME, linkName +
    // "/deviceName"),
    // stringType,
    // "The name of the serial port device.");
    //
    // deviceHKParam = mdb.createSystemParameter(qualifiedName(YAMCS_SPACESYSTEM_NAME, linkName +
    // "/SerialPortHK"),
    // spDeviceHKType,
    // "Housekeeping information. Status of the device, name, etc");

    // TODO Fix this
    // Extract the last token of the class name, since it will be in the form of
    // PackageA.PackageB.ClassName

    // String[] classNameParts = TmLink.getPacketInputStream().getClass().getName().split(".");
    // classNameParts[classNameParts.length-1];
    String packInputStreamClassName = "PacketInputStream";

    // if (TmLink.getConfig().containsKey("packetInputStreamClassName")) {
    // packInputStreamClassName = TmLink.getConfig().getString("packetInputStreamClassName");
    // }

    spSerialTmFrameLinkHKType =
        new AggregateParameterType.Builder()
            .setName("SerialTmFrameLink_HK")
            .addMember(new Member("Mission_Time", sysParamCollector.getBasicType(Type.TIMESTAMP)))
            .addMember(new Member("Time", sysParamCollector.getBasicType(Type.TIMESTAMP)))
            .build();
    SerialTmFrameLinkHKParam =
        mdb.createSystemParameter(
            qualifiedName(
                YAMCS_SPACESYSTEM_NAME,
                linkName
                    + "/SerialTmFrame"
                    + NameDescription.PATH_SEPARATOR
                    + "SerialTmFrameLink_HK"),
            spSerialTmFrameLinkHKType,
            "Housekeeping information. Status of SerialTmFrameLink.");
  }
}
