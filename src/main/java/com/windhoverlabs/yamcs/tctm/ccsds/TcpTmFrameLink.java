package com.windhoverlabs.yamcs.tctm.ccsds;

import java.io.EOFException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;

import org.yamcs.ConfigurationException;
import org.yamcs.TmPacket;
import org.yamcs.YConfiguration;
import org.yamcs.tctm.TcTmException;
import org.yamcs.tctm.ccsds.AbstractTmFrameLink;
import org.yamcs.utils.StringConverter;
import org.openmuc.jrxtx.SerialPort;
import org.openmuc.jrxtx.SerialPortBuilder;
import org.yamcs.tctm.PacketInputStream;
import org.yamcs.tctm.PacketPreprocessor;
import org.yamcs.tctm.PacketTooLongException;
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
public class TcpTmFrameLink extends AbstractTmFrameLink implements Runnable {
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

    protected Socket tmSocket;
    protected String host;
    protected int port;
    protected PacketPreprocessor packetPreprocessor;


    /**
     * Creates a new Serial Frame Data Link
     * 
     * @throws ConfigurationException
     *             if port is not defined in the configuration
     */
    public void init(String instance, String name, YConfiguration config) throws ConfigurationException {
        super.init(instance, name, config);
        
        super.init(instance, name, config);
        if (config.containsKey("tmHost")) { // this is when the config is specified in tcp.yaml
            host = config.getString("tmHost");
            port = config.getInt("tmPort");
        } else {
            host = config.getString("host");
            port = config.getInt("port");
        }
        initialDelay = config.getLong("initialDelay", -1);

        if (config.containsKey("packetInputStreamClassName")) {
            this.packetInputStreamClassName = config.getString("packetInputStreamClassName");
            this.packetInputStreamArgs = config.getConfig("packetInputStreamArgs");
        } else {
            this.packetInputStreamClassName = CcsdsPacketInputStream.class.getName();
            this.packetInputStreamArgs = YConfiguration.emptyConfig();
        }


        if (config.containsKey("packetInputStreamClassName")) {
            this.packetInputStreamClassName = config.getString("packetInputStreamClassName");
            this.packetInputStreamArgs = config.getConfig("packetInputStreamArgs");
        } else {
            this.packetInputStreamClassName = CcsdsPacketInputStream.class.getName();
            this.packetInputStreamArgs = YConfiguration.emptyConfig();
        }
    }
    
    protected void openSocket() throws IOException {
        InetAddress address = InetAddress.getByName(host);
        tmSocket = new Socket();
        tmSocket.setKeepAlive(true);
        tmSocket.connect(new InetSocketAddress(address, port), 1000);
        try {
            packetInputStream = YObjectLoader.loadObject(packetInputStreamClassName);
        } catch (ConfigurationException e) {
            log.error("Cannot instantiate the packetInput stream", e);
            throw e;
        }
        packetInputStream.init(tmSocket.getInputStream(), packetInputStreamArgs);
    }
    
    @Override
    public void doStart() {
        if (!isDisabled()) {
            doEnable();
        }
        notifyStarted();
    }

    @Override
    public void doStop() {
        if (thread != null) {
            thread.interrupt();
        }
        if (tmSocket != null) {
            try {
                tmSocket.close();
            } catch (IOException e) {
                log.warn("Exception got when closing the tm socket:", e);
            }
            tmSocket = null;
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
//            	System.out.println("run#1");
//                TmPacket tmpkt = getNextPacket();
//                System.out.println("run#2");
//                if (tmpkt == null) {
//                    break;
//                }
//                processPacket(tmpkt);
            	
                if (tmSocket == null) {
                	System.out.println("getNextPacket#2");
                    openSocket();
                    log.info("Link established to {}:{}", host, port);
                }

                byte[] packet = packetInputStream.readPacket();
                
            	System.out.println("getNextPacket#3");

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
    public void doDisable() {
        if (tmSocket != null) {
            try {
                tmSocket.close();
            } catch (IOException e) {
                log.warn("Exception got when closing the tm socket:", e);
            }
            tmSocket = null;
        }
        if (thread != null) {
            thread.interrupt();
        }
    }

    @Override
    public void doEnable() {
        thread = new Thread(this);
        thread.setName(this.getClass().getSimpleName() + "-" + linkName);
        thread.start();
    }
    @Override
    protected Status connectionStatus() {
        return (serialPort == null) ? Status.DISABLED : Status.OK;
    }
    
    

    public TmPacket getNextPacket() {
        TmPacket pwt = null;
        System.out.println("getNextPacket#1");
        while (isRunningAndEnabled()) {
            try {
                if (tmSocket == null) {
                	System.out.println("getNextPacket#2");
                    openSocket();
                    log.info("Link established to {}:{}", host, port);
                }
                System.out.println("getNextPacket#3");
                byte[] packet = packetInputStream.readPacket();
                System.out.println("getNextPacket#4");
//                updateStats(packet.length);
                TmPacket pkt = new TmPacket(timeService.getMissionTime(), packet);
                System.out.println("getNextPacket#5");
                pkt.setEarthRceptionTime(timeService.getHresMissionTime());
                System.out.println("getNextPacket#6");
                pwt = packetPreprocessor.process(pkt);
                if (pwt != null) {
                    break;
                }
            } catch (IOException e) {
                if (isRunningAndEnabled()) {
                    String msg;
                    if (e instanceof EOFException) {
                        msg = "TM socket connection to " + host + ":" + port + " closed. Reconnecting in 10s.";
                    } else {
                        msg = "Cannot open or read TM socket " + host + ": " + port + ": "
                                + ((e instanceof ConnectException) ? e.getMessage() : e.toString())
                                + ". Retrying in 10 seconds.";
                    }
                    log.warn(msg);
                }
                forceClosedSocket();
                try {
                    Thread.sleep(10000);
                } catch (InterruptedException e1) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            } catch (PacketTooLongException e) {
                log.warn(e.toString());
                forceClosedSocket();
            }
        }
        return pwt;
    }

    public void setSerialPort(SerialPort newSerialPort) {
        serialPort = newSerialPort;
    }
    
    public PacketInputStream getPacketInputStream() {
        return packetInputStream;
    }
    
    private void forceClosedSocket() {
        if (tmSocket != null) {
            try {
                tmSocket.close();
            } catch (Exception e2) {
            }
        }
        tmSocket = null;
    }
}
