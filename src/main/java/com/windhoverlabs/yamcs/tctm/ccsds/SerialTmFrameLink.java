package com.windhoverlabs.yamcs.tctm.ccsds;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketException;

import org.yamcs.ConfigurationException;
import org.yamcs.YConfiguration;
import org.yamcs.tctm.TcTmException;
import org.yamcs.tctm.ccsds.AbstractTmFrameLink;
import org.yamcs.utils.StringConverter;
import org.openmuc.jrxtx.SerialPort;
import org.openmuc.jrxtx.SerialPortBuilder;
import org.yamcs.tctm.PacketInputStream;
import org.yamcs.tctm.CcsdsPacketInputStream;
import org.yamcs.utils.YObjectLoader;
import org.yamcs.xtce.AggregateParameterType;
import org.yamcs.xtce.Parameter;

/**
 * Receives telemetry fames via serial interface.
 * 
 * 
 * @author Mathew Benson (mbenson@windhoverlabs.com)
 *
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

    /**
     * Creates a new Serial Frame Data Link
     * 
     * @throws ConfigurationException
     *             if port is not defined in the configuration
     */
    public void init(String instance, String name, YConfiguration config) throws ConfigurationException {
        super.init(instance, name, config);

        this.deviceName = config.getString("device", "/dev/ttyUSB0");
        this.syncSymbol = config.getString("syncSymbol", "");
        this.baudRate = config.getInt("baudRate", 57600);
        this.initialDelay = config.getLong("initialDelay", -1);
        this.dataBits = config.getInt("dataBits", 8);
        this.stopBits = config.getString("stopBits", "1");
        this.parity = config.getString("parity", "NONE");
        this.flowControl = config.getString("flowControl", "NONE");

        if (this.parity != "NONE" && this.parity != "EVEN" && this.parity != "ODD" && this.parity != "MARK"
                && this.parity != "SPACE") {
            throw new ConfigurationException("Invalid Parity (NONE, EVEN, ODD, MARK or SPACE)");
        }

        if (this.flowControl != "NONE" && this.flowControl != "RTS_CTS" && this.flowControl != "XON_XOFF") {
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
                    log.trace("Received packet of length {}: {}", length, StringConverter
                            .arrayToHexString(packet, 0, length, true));
                }
                if (length < frameHandler.getMinFrameSize()) {
                    eventProducer.sendWarning("Error processing frame: size " + length
                            + " shorter than minimum allowed " + frameHandler.getMinFrameSize());
                    continue;
                }
                if (length > frameHandler.getMaxFrameSize()) {
                    eventProducer.sendWarning("Error processing frame: size " + length + " longer than maximum allowed "
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

    /**
     * returns statistics with the number of datagram received and the number of invalid datagrams
     */
    @Override
    public String getDetailedStatus() {
        if (isDisabled()) {
            return "DISABLED";
        } else {
            return String.format("OK (%s) %nValid datagrams received: %d%n",
                    deviceName, frameCount.get());
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
                log.info("Cannot open or read serial device {}::{}:{}'. Retrying in 10s", deviceName, e.getMessage(),
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
}
