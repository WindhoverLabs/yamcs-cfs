package com.windhoverlabs.yamcs.tctm;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.WritableByteChannel;
import java.util.HashMap;
import java.util.Map;
import org.openmuc.jrxtx.SerialPort;
import org.openmuc.jrxtx.SerialPortBuilder;
import org.yamcs.ConfigurationException;
import org.yamcs.YConfiguration;
import org.yamcs.YamcsServer;
import org.yamcs.commanding.PreparedCommand;
import org.yamcs.tctm.AbstractThreadedTcDataLink;
import org.yamcs.tctm.GenericCommandPostprocessor;
import org.yamcs.tctm.Link.Status;

public class SerialATTcDatalink extends AbstractThreadedTcDataLink {
  protected String deviceName;
  protected int baudRate;
  protected int dataBits;
  protected String stopBits;
  protected String parity;
  protected String flowControl;
  protected long initialDelay;

  SerialPort serialPort = null;
  OutputStream outputStream = null;

  @Override
  public void init(String instance, String name, YConfiguration config)
      throws ConfigurationException {
    super.init(instance, name, config);
    System.out.println("Initialized 1" + this.getClass().getName());
    timeService = YamcsServer.getTimeService(yamcsInstance);

    this.deviceName = config.getString("device", "/dev/ttyUSB0");
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

    log.warn("Initialized 2" + this.getClass().getName());
  }

  @Override
  protected void initPostprocessor(String instance, YConfiguration config) {
    // traditionally this has used by default the ISS post-processor
    Map<String, Object> m = null;
    if (config == null) {
      m = new HashMap<>();
      config = YConfiguration.wrap(m);
    } else if (!config.containsKey("commandPostprocessorClassName")) {
      m = config.getRoot();
    }
    if (m != null) {
      log.warn(
          "Please set the commandPostprocessorClassName for the TcpTcDataLink; in the future versions it will default to GenericCommandPostprocessor");
      m.put("commandPostprocessorClassName", GenericCommandPostprocessor.class.getName());
    }
    super.initPostprocessor(instance, config);
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

    this.outputStream = serialPort.getOutputStream();
  }

  @Override
  protected void uplinkCommand(PreparedCommand pc) throws IOException {
    byte[] binary = cmdPostProcessor.process(pc);
    System.out.println("uplinkCommand1");
    if (binary == null) {
      log.warn("command postprocessor did not process the command");
      return;
    }

    System.out.println("uplinkCommand2");
    int retries = 5;
    boolean sent = false;

    ByteBuffer bb = ByteBuffer.wrap(binary);
    System.out.println("uplinkCommand3");
    bb.rewind();
    String reason = null;
    while (!sent && (retries > 0)) {
      System.out.println("uplinkCommand4");
      try {
        if (serialPort == null) {
          openDevice();
        }
        WritableByteChannel channel = Channels.newChannel(outputStream);
        channel.write(bb);
        dataCount.getAndIncrement();
        sent = true;
      } catch (IOException e) {
        reason = String.format("Error writing to TC device to %s : %s", deviceName, e.getMessage());
        log.warn(reason);
        try {
          if (serialPort != null) {
            serialPort.close();
          }
          serialPort = null;
        } catch (IOException e1) {
          e1.printStackTrace();
        }
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
    if (sent) {
      ackCommand(pc.getCommandId());
    } else {
      failedCommand(pc.getCommandId(), reason);
    }
  }

  @Override
  protected void startUp() throws Exception {
    if (!isDisabled()) {
      openDevice();
    }
  }

  @Override
  protected void shutDown() throws Exception {
    if (serialPort != null) {
      try {
        serialPort.close();
      } catch (IOException e) {
        log.warn("Exception got when closing the serial port:", e);
      }
      serialPort = null;
    }
  }

  @Override
  protected Status connectionStatus() {
    return (serialPort == null) ? Status.UNAVAIL : Status.OK;
  }

  @Override
  public String getDetailedStatus() {
    if (isDisabled()) {
      return String.format("DISABLED (should connect to %s)", deviceName);
    }
    if (serialPort == null) {
      return String.format("Not connected to %s", deviceName);
    } else {
      return String.format("OK, connected to %s, sent %d commands", deviceName, dataCount.get());
    }
  }
}
