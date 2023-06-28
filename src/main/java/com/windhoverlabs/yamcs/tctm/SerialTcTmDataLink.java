package com.windhoverlabs.yamcs.tctm;

import static org.yamcs.cmdhistory.CommandHistoryPublisher.AcknowledgeSent_KEY;

import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import com.fazecast.jSerialComm.SerialPort;
import java.util.HashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.yamcs.tctm.AbstractTmDataLink;
import org.yamcs.tctm.TcDataLink;
import org.yamcs.tctm.CommandPostprocessor;
import org.yamcs.tctm.AggregatedDataLink;
import org.yamcs.tctm.PacketInputStream;
import org.yamcs.tctm.GenericPacketInputStream;
import org.yamcs.tctm.GenericCommandPostprocessor;
import org.yamcs.tctm.PacketTooLongException;
import org.yamcs.ConfigurationException;
import org.yamcs.TmPacket;
import org.yamcs.YConfiguration;
import org.yamcs.cmdhistory.CommandHistoryPublisher;
import org.yamcs.cmdhistory.CommandHistoryPublisher.AckStatus;
import org.yamcs.commanding.PreparedCommand;
import org.yamcs.protobuf.Commanding.CommandId;
import org.yamcs.utils.TimeEncoding;
import org.yamcs.utils.YObjectLoader;

//public class SerialTcTmDataLink extends AbstractTmDataLink implements TcDataLink, Runnable {
public class SerialTcTmDataLink extends AbstractTmDataLink implements Runnable {

    protected CommandHistoryPublisher commandHistoryPublisher;
    protected AtomicLong dataOutCount = new AtomicLong();
    protected CommandPostprocessor cmdPostProcessor;
    private AggregatedDataLink parent = null;

    // MARK: - Copied and modified from AbstractTmDataLink

    protected String deviceName;
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
    OutputStream outputStream;

    @Override
    public void init(String instance, String name, YConfiguration config) throws ConfigurationException {
        super.init(instance, name, config);
        
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

        initialDelay = config.getLong("initialDelay", -1);
        // Input stream defaults to GenericPacketInputStream
        if (config.containsKey("packetInputStreamClassName")) {
          this.packetInputStreamClassName = config.getString("packetInputStreamClassName");
          this.packetInputStreamArgs = config.getConfig("packetInputStreamArgs");
        } else {
            this.packetInputStreamClassName = GenericPacketInputStream.class.getName();
            HashMap<String, Object> m = new HashMap<>();
            m.put("maxPacketLength", 1000);
            m.put("lengthFieldOffset", 5);
            m.put("lengthFieldLength", 2);
            m.put("lengthAdjustment", 7);
            m.put("initialBytesToStrip", 0);
            this.packetInputStreamArgs = YConfiguration.wrap(m);
        }

        // Setup tc postprocessor
        initPostprocessor(yamcsInstance, config);
    }    
    

  protected synchronized void checkAndOpenDevice() throws IOException {
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
      
      this.outputStream = this.serialPort.getOutputStream();
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

  }
    
    
    
    
    
    
    

    protected synchronized boolean isSerialPortOpen() {
        return this.serialPort != null;
    }

    protected synchronized void sendBuffer(byte[] data) throws IOException {
        if (outputStream == null) {
            throw new IOException(String.format("No connection to %s", deviceName));
        }
        outputStream.write(data);
    }

    protected synchronized void closeSerialPort() {
        if (serialPort != null) {
            serialPort.closePort();
            serialPort = null;
            outputStream = null;
            packetInputStream = null;
        }
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
            TmPacket tmpkt = getNextPacket();
            if (tmpkt != null) {
                processPacket(tmpkt);
            }
        }
    }

    public TmPacket getNextPacket() {
        TmPacket pwt = null;
        
        while (isRunning()) {
            try {
                checkAndOpenDevice();
                byte[] packet = packetInputStream.readPacket();
                
                updateStats(packet.length);
                TmPacket pkt = new TmPacket(timeService.getMissionTime(), packet);
                pkt.setEarthRceptionTime(timeService.getHresMissionTime());
                pwt = packetPreprocessor.process(pkt);
                if (pwt != null) {
                    break;
                }
            } catch (EOFException e) {
                log.warn("TM Connection closed");
                closeSerialPort();
            } catch (IOException e) {
                if (isRunningAndEnabled()) {
                    log.info("Cannot open or read TM socket {}'. Retrying in 10s", e.toString());
                }
                closeSerialPort();
                for (int i = 0; i < 10; i++) {
                    if (!isRunningAndEnabled()) {
                        break;
                    }
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException e1) {
                        Thread.currentThread().interrupt();
                        return null;
                    }
                }
            } catch (PacketTooLongException e) {
                log.warn(e.toString());
                closeSerialPort();
            }
        }
        return pwt;
    }

    // MARK: - Stuff copied from AbstractTcDataLink

    protected void initPostprocessor(String instance, YConfiguration config) throws ConfigurationException {
        String commandPostprocessorClassName = GenericCommandPostprocessor.class.getName();
        YConfiguration commandPostprocessorArgs = null;

        // The GenericCommandPostprocessor class does nothing if there are no arguments, which is what we want.
        if (config != null) {
            commandPostprocessorClassName = config.getString("commandPostprocessorClassName",
                    GenericCommandPostprocessor.class.getName());
            if (config.containsKey("commandPostprocessorArgs")) {
                commandPostprocessorArgs = config.getConfig("commandPostprocessorArgs");
            }
        }

        // Instantiate
        try {
            if (commandPostprocessorArgs != null) {
                cmdPostProcessor = YObjectLoader.loadObject(commandPostprocessorClassName, instance,
                        commandPostprocessorArgs);
            } else {
                cmdPostProcessor = YObjectLoader.loadObject(commandPostprocessorClassName, instance);
            }
        } catch (ConfigurationException e) {
            log.error("Cannot instantiate the command postprocessor", e);
            throw e;
        }
    }

    // MARK: - TcDataLink

//    @Override
    public boolean sendCommand(PreparedCommand pc) {
        byte[] binary = pc.getBinary();
        if (!pc.disablePostprocessing()) {
            binary = cmdPostProcessor.process(pc);
            if (binary == null) {
                log.warn("command postprocessor did not process the command");
                return true;
            }
        }

        try {
            sendBuffer(binary);
            dataOutCount.getAndIncrement();
            ackCommand(pc.getCommandId());
            return true;
        } catch (IOException e) {
            String reason = String.format("Error writing to to %s:%d; %s", deviceName, e.toString());
            log.warn(reason);
            failedCommand(pc.getCommandId(), reason);
            return true;
        }
    }

//    @Override
    public void setCommandHistoryPublisher(CommandHistoryPublisher commandHistoryListener) {
        this.commandHistoryPublisher = commandHistoryListener;
        cmdPostProcessor.setCommandHistoryPublisher(commandHistoryListener);
    }

    /** Send to command history the failed command */
    protected void failedCommand(CommandId commandId, String reason) {
        log.debug("Failing command {}: {}", commandId, reason);
        long currentTime = getCurrentTime();
        commandHistoryPublisher.publishAck(commandId, AcknowledgeSent_KEY, currentTime, AckStatus.NOK, reason);
        commandHistoryPublisher.commandFailed(commandId, currentTime, reason);
    }

    /**
     * send an ack in the command history that the command has been sent out of the link
     * 
     * @param commandId
     */
    protected void ackCommand(CommandId commandId) {
        commandHistoryPublisher.publishAck(commandId, AcknowledgeSent_KEY, getCurrentTime(), AckStatus.OK);
    }

    // MARK: - AbstractService (com.google.common.util.concurrent.AbstractService)

    @Override
    public void doStart() {
        if (!isDisabled()) {
            Thread thread = new Thread(this);
            thread.setName("SerialTcTmDataLink-" + linkName);
            thread.start();
        }
        notifyStarted();
    }

    @Override
    public void doStop() {
        if (serialPort != null) {
            closeSerialPort();
        }
        notifyStopped();
    }

    // MARK: - AbstractLink

    @Override
    protected long getCurrentTime() {
        if (timeService != null) {
            return timeService.getMissionTime();
        }
        return TimeEncoding.getWallclockTime();
    }

    @Override
    public long getDataOutCount() {
        return dataOutCount.get();
    }

    @Override
    public void resetCounters() {
        super.resetCounters();
        dataOutCount.set(0);
    }

    @Override
    public AggregatedDataLink getParent() {
        return parent;
    }

    @Override
    public void setParent(AggregatedDataLink parent) {
        this.parent = parent;
    }

    @Override
    public void doDisable() {
        closeSerialPort();
    }

    @Override
    public void doEnable() {
        new Thread(this).start();
    }

    // MARK: - Link

    @Override
    public String getDetailedStatus() {
        if (isDisabled()) {
            return String.format("DISABLED (should connect to %s)", deviceName);
        }
        if (isSerialPortOpen()) {
            return String.format("Not connected to %s", deviceName);
        } else {
            return String.format("OK, connected to %s, received %d packets", deviceName, packetCount.get());
        }
    }

    @Override
    protected Status connectionStatus() {
        return !isSerialPortOpen() ? Status.UNAVAIL : Status.OK;
    }
}
