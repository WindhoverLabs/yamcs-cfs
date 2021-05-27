package com.windhoverlabs.yamcs.tctm.ccsds;

import java.io.IOException;
import java.util.List;

import org.openmuc.jrxtx.SerialPort;
import org.openmuc.jrxtx.SerialPortBuilder;
import org.yamcs.ConfigurationException;
import org.yamcs.YConfiguration;
import org.yamcs.cmdhistory.CommandHistoryPublisher;
import org.yamcs.commanding.PreparedCommand;
import org.yamcs.tctm.AbstractLink;
import org.yamcs.tctm.AggregatedDataLink;
import org.yamcs.tctm.Link;
import org.yamcs.tctm.TcDataLink;

/**
 * A link that uses one serial port for sending commands and receiving telemetry.
 * It aggregates the SerialTmFrameLink and SerialTcFrameLink links.
 * @author lgomez
 *
 */
public class SerialTmTcFrameLink extends AbstractLink implements Runnable, TcDataLink, AggregatedDataLink {

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

    @Override
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

        try {
            openDevice();
        } catch (IOException e) {
            // TODO Auto-generated catch block
            log.warn("Failed to open serial port.");
        }

        TcLink = new SerialTcFrameLink();
        TmLink = new SerialTmFrameLink();
        TmLink.setSerialPort(serialPort);
        TcLink.setSerialPort(serialPort);

        TmLink.init(instance, name, config.getConfig("tm_config"));
        TcLink.init(instance, name, config.getConfig("tc_config"));
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
        TmLink.doEnable();
        TcLink.doEnable();
        new Thread(this).start();
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
        TmLink.doStop();
        TcLink.doStop();

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
        while (isRunningAndEnabled());
    }

    @Override
    protected Status connectionStatus() {
        return (serialPort == null) ? Status.DISABLED : Status.OK;
    }

    @Override
    public List<Link> getSubLinks() {
        // TODO Auto-generated method stub
        List<Link> subLinks = TcLink.getSubLinks();
        subLinks.addAll(TmLink.getSubLinks());
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
}
