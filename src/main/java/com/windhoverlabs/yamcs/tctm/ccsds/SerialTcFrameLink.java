package com.windhoverlabs.yamcs.tctm.ccsds;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import org.yamcs.ConfigurationException;
import org.yamcs.YConfiguration;
import org.yamcs.utils.StringConverter;
import org.openmuc.jrxtx.SerialPort;
import org.openmuc.jrxtx.SerialPortBuilder;
import org.yamcs.commanding.PreparedCommand;
import org.yamcs.tctm.ccsds.AbstractTcFrameLink;
import org.yamcs.tctm.ccsds.TcTransferFrame;

import com.google.common.primitives.Bytes;
import com.google.common.util.concurrent.RateLimiter;

/**
 * Send command fames via serial interface.
 * 
 * 
 * @author Mathew Benson (mbenson@windhoverlabs.com)
 *
 */
public class SerialTcFrameLink extends AbstractTcFrameLink implements Runnable {
    RateLimiter rateLimiter;
    protected String deviceName;
    protected String syncSymbol;
    protected int baudRate;
    protected int dataBits;
    protected String stopBits;
    protected String parity;
    protected String flowControl;
    protected long initialDelay;

    Map<Integer, TcTransferFrame> pendingFrames = new ConcurrentHashMap<>();

    SerialPort serialPort = null;
    Thread thread;
    
    /**
     * Creates a new Serial Frame Data Link
     * 
     * @throws ConfigurationException
     *             if port is not defined in the configuration
     */
    public void init(String instance, String name, YConfiguration config) throws ConfigurationException {
        super.init(instance, name, config);

        if (config.containsKey("frameMaxRate")) {
            rateLimiter = RateLimiter.create(config.getDouble("frameMaxRate"), 1, TimeUnit.SECONDS);
        }

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
    }

    @Override
    public void run() {
        while (isRunningAndEnabled()) {
            if (rateLimiter != null) {
                rateLimiter.acquire();
            }
            TcTransferFrame tf = multiplexer.getFrame();
            if (tf != null) {
                byte[] data = tf.getData();
                if (log.isTraceEnabled()) {
                    log.trace("Outgoing frame data: {}", StringConverter.arrayToHexString(data, true));
                }

                if (cltuGenerator != null) {
                    data = cltuGenerator.makeCltu(data);
                    if (log.isTraceEnabled()) {
                        log.trace("Outgoing CLTU: {}", StringConverter.arrayToHexString(data, true));
                    }
                }

                if (tf.isBypass()) {
                    ackBypassFrame(tf);
                }

                OutputStream outStream = null;
                try {
                    outStream = serialPort.getOutputStream();
                } catch (IOException e1) {
                    // TODO Auto-generated catch block
                    e1.printStackTrace();
                }
                try {
                    outStream.write(data);

                } catch (IOException e) {
                    // TODO Auto-generated catch block
                    e.printStackTrace();
                }
                frameCount++;

            }
        }
    }

    @Override
    protected void doDisable() throws Exception {
        if (thread != null) {
            thread.interrupt();
        }

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
    protected void doEnable() throws Exception {
        if (serialPort == null) {
            openDevice();
            log.info("Listening on {}", deviceName);
        }
        thread = new Thread(this);
        thread.start();
    }

    @Override
    protected void doStart() {
        try {
            if (!isDisabled()) {
                if (serialPort == null) {
                    openDevice();
                    log.info("Bound to {}", deviceName);
                }
            }

            doEnable();
            notifyStarted();
        } catch (Exception e) {
            log.warn("Exception starting link", e);
            notifyFailed(e);
        }
    }

    @Override
    protected void doStop() {
        try {
            if (serialPort != null) {
                try {
                    log.info("Closing {}", deviceName);
                    serialPort.close();
                } catch (IOException e) {
                    log.warn("Exception raised closing the serial port:", e);
                }
                serialPort = null;
            }

            doDisable();
            multiplexer.quit();
            notifyStopped();
        } catch (Exception e) {
            log.warn("Exception stopping link", e);
            notifyFailed(e);
        }
    }

    @Override
    protected Status connectionStatus() {
        return (serialPort == null) ? Status.DISABLED : Status.OK;
    }

    protected void openDevice() {
        try {
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

    @Override
    public void sendTc(PreparedCommand pc) {
        //Not used when framing commands.
    }

    public void setSerialPort(SerialPort inSerialPort) {
        serialPort = inSerialPort;  
    }
}
