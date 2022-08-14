package com.windhoverlabs.yamcs.tctm;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.openmuc.jrxtx.SerialPort;
import org.openmuc.jrxtx.SerialPortBuilder;
import org.yamcs.ConfigurationException;
import org.yamcs.YConfiguration;
import org.yamcs.parameter.ParameterValue;
import org.yamcs.tctm.AbstractLink;
import org.yamcs.tctm.AggregatedDataLink;
import org.yamcs.tctm.Link;
import org.yamcs.xtce.Parameter;
import org.yamcs.xtce.XtceDb;

public class SerialATTmTcDatalink extends AbstractLink implements Runnable, AggregatedDataLink {
  protected String deviceName;
  protected int baudRate;
  protected int dataBits;
  protected String stopBits;
  protected String parity;
  protected String flowControl;
  protected long initialDelay;

  String packetInputStreamClassName;
  YConfiguration packetInputStreamArgs;
  SerialPort serialPort = null;
  private XtceDb mdb;
  private Parameter spPort;

  SerialTmDatalink TmLink = null;
  SerialTcDatalink TcLink = null;
  private Thread thread;

  @Override
  public void init(String instance, String name, YConfiguration config)
      throws ConfigurationException {
    super.init(instance, name, config);

    this.deviceName = config.getString("device", "/dev/ttyUSB0");
    this.baudRate = config.getInt("baudRate", 57600);
    this.initialDelay = config.getLong("initialDelay", -1);
    this.dataBits = config.getInt("dataBits", 8);
    this.stopBits = config.getString("stopBits", "1");
    this.parity = config.getString("parity", "NONE");
    this.flowControl = config.getString("flowControl", "NONE");

    if (this.parity != "NONE"
        && this.parity != "EVEN"
        && this.parity != "ODD"
        && this.parity != "MARK"
        && this.parity != "SPACE") {
      throw new ConfigurationException("Invalid Parity (NONE, EVEN, ODD, MARK or SPACE)");
    }

    if (this.flowControl != "NONE"
        && this.flowControl != "RTS_CTS"
        && this.flowControl != "XON_XOFF") {
      throw new ConfigurationException("Invalid Flow Control (NONE, RTS_CTS, or XON_XOFF)");
    }

    if (this.dataBits != 5 && this.dataBits != 6 && this.dataBits != 7 && this.dataBits != 8) {
      throw new ConfigurationException("Invalid Data Bits (5, 6, 7, or 8)");
    }

    if (this.stopBits != "1" && this.stopBits != "1.5" && this.stopBits != "2") {
      throw new ConfigurationException("Invalid Stop Bits (1, 1.5, or 2)");
    }

    if (config.containsKey("packetInputStreamClassName")) {
      this.packetInputStreamClassName = config.getString("packetInputStreamClassName");
      this.packetInputStreamArgs = config.getConfig("packetInputStreamArgs");
    } else {
      this.packetInputStreamClassName = CcsdsPacketInputStream.class.getName();
      this.packetInputStreamArgs = YConfiguration.emptyConfig();
    }

    log.info("Initialized " + this.getClass().getName());

    try {
      openDevice();
    } catch (IOException e) {
      // TODO Auto-generated catch block
      log.warn("Failed to open serial port.");
    }

    TcLink = new SerialTcDatalink();
    TmLink = new SerialTmDatalink();
    TcLink.setParent(this);
    TmLink.setParent(this);
    TcLink.init(instance, name + "_tc", config.getConfig("tc_config"));

    TmLink.setSerialPort(serialPort);
    TcLink.setSerialPort(serialPort);

    TmLink.init(instance, name + "_tm", config.getConfig("tm_config"));
  }

  @Override
  public void run() {
    while (isRunningAndEnabled())
      ;
  }

  @Override
  protected Status connectionStatus() {
    return (serialPort == null) ? Status.UNAVAIL : Status.OK;
  }

  protected void openDevice() throws IOException {
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

    //    try {
    //      packetInputStream = YObjectLoader.loadObject(packetInputStreamClassName);
    //    } catch (ConfigurationException e) {
    //      log.error("Cannot instantiate the packetInput stream", e);
    //      throw e;
    //    }
  }

  @Override
  protected void doStart() {
    if (!isDisabled()) {
      new Thread(this).start();
    }
    notifyStarted();
  }

  @Override
  protected void doStop() {
    if (serialPort != null) {
      try {
        serialPort.close();
      } catch (IOException e) {
        log.warn("Exception got when closing the serial port:", e);
      }
      serialPort = null;
    }
    notifyStopped();
  }

  @Override
  public void doDisable() {
    System.out.println("SerialATTmTcDatalink--->doDisable****");
    //    if (serialPort != null) {
    //      try {
    //        serialPort.close();
    //      } catch (IOException e) {
    //        log.warn("Exception got when closing the serial port:", e);
    //      }
    //      serialPort = null;
    //    }

    TmLink.disable();
    TcLink.disable();
  }

  @Override
  public void doEnable() throws Exception {
    if (serialPort == null) {
      openDevice();
      log.info("Listening on {}", deviceName);
    }
    TcLink.setSerialPort(serialPort);
    TmLink.setSerialPort(serialPort);
    TmLink.doEnable();
    TcLink.doEnable();

    //    thread = new Thread(this);
    //    thread.start();
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
    //        list.add(SystemParametersService.getPV(spPort, time, 1054));

  }

  @Override
  public String getDetailedStatus() {
    if (isDisabled()) {
      return String.format("DISABLED (should connect to %s)", deviceName);
    }
    if (serialPort == null) {
      return String.format("Not connected to %s", deviceName);
    } else {
      //      return String.format(
      //          "OK, connected to %s, received %d packets", deviceName, packetCount.get());
    }

    return "";
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
  public List<Link> getSubLinks() {
    List<Link> subLinks = new ArrayList<Link>();
    subLinks.add(TcLink);
    subLinks.add(TmLink);

    return subLinks;
  }
}
