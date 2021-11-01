package com.windhoverlabs.yamcs.tctm.ccsds;

import static org.yamcs.cmdhistory.CommandHistoryPublisher.AcknowledgeSent;

import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.util.concurrent.atomic.AtomicLong;

import org.yamcs.ConfigurationException;
import org.yamcs.TmPacket;
import org.yamcs.YConfiguration;
import org.yamcs.cmdhistory.CommandHistoryPublisher;
import org.yamcs.cmdhistory.CommandHistoryPublisher.AckStatus;
import org.yamcs.commanding.PreparedCommand;
import org.yamcs.protobuf.Commanding.CommandId;
import org.yamcs.tctm.TcTmException;
import org.yamcs.tctm.ccsds.AbstractTmFrameLink;
import org.yamcs.utils.StringConverter;
import org.openmuc.jrxtx.SerialPort;
import org.openmuc.jrxtx.SerialPortBuilder;
import org.yamcs.tctm.PacketInputStream;
import org.yamcs.tctm.PacketPreprocessor;
import org.yamcs.tctm.PacketTooLongException;
import org.yamcs.tctm.TcDataLink;
import org.yamcs.tctm.AggregatedDataLink;
import org.yamcs.tctm.CcsdsPacketInputStream;
import org.yamcs.tctm.CommandPostprocessor;
import org.yamcs.tctm.GenericCommandPostprocessor;
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
public class TcpTmTcFrameLink extends AbstractTmFrameLink implements Runnable, TcDataLink {
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

	// Stuff copied from AbstractTcDataLink
	protected CommandHistoryPublisher commandHistoryPublisher;
	protected AtomicLong dataOutCount = new AtomicLong();
	protected CommandPostprocessor cmdPostProcessor;
	private AggregatedDataLink parent = null;
	OutputStream outputStream;

	/**
	 * Creates a new Serial Frame Data Link
	 * 
	 * @throws ConfigurationException if port is not defined in the configuration
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

		initPostprocessor(yamcsInstance, config);
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
					checkAndOpenSocket();
					log.info("Link established to {}:{}", host, port);
				}

				byte[] packet = packetInputStream.readPacket();

//                byte[] packet  = tmSocket.getInputStream().readNBytes(1024);

				System.out.println("getNextPacket#3");

				System.out.println("Contents of packet:" + StringConverter.arrayToHexString(packet));

				int length = packet.length;
				if (log.isTraceEnabled()) {
					log.trace("Received packet of length {}: {}", length,
							StringConverter.arrayToHexString(packet, 0, length, true));
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
	 * returns statistics with the number of datagram received and the number of
	 * invalid datagrams
	 */
	@Override
	public String getDetailedStatus() {
		if (isDisabled()) {
			return "DISABLED";
		} else {
			return String.format("OK (%s) %nValid datagrams received: %d%n", deviceName, frameCount.get());
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
	
    protected synchronized void checkAndOpenSocket() throws IOException {
        if (tmSocket != null) {
            return;
        }
        InetAddress address = InetAddress.getByName(host);
        tmSocket = new Socket();
        tmSocket.setKeepAlive(true);
        tmSocket.connect(new InetSocketAddress(address, port), 1000);
        try {
            packetInputStream = YObjectLoader.loadObject(packetInputStreamClassName);
            outputStream = tmSocket.getOutputStream();
        } catch (ConfigurationException e) {
            log.error("Cannot instantiate the packetInput: " + e);
            try {
                tmSocket.close();
            } catch (IOException e2) {
            }
            tmSocket = null;
            outputStream = null;
            packetInputStream = null;
            throw e;
        }
        packetInputStream.init(tmSocket.getInputStream(), packetInputStreamArgs);
        log.info("Link established to {}:{}", host, port);
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

	protected void initPostprocessor(String instance, YConfiguration config) throws ConfigurationException {
		String commandPostprocessorClassName = GenericCommandPostprocessor.class.getName();
		YConfiguration commandPostprocessorArgs = null;

		// The GenericCommandPostprocessor class does nothing if there are no arguments,
		// which is what we want.
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

	protected synchronized void sendBuffer(byte[] data) throws IOException {
		if (outputStream == null) {
			throw new IOException(String.format("No connection to %s:%d", host, port));
		}
		outputStream.write(data);
	}

	// MARK: - TcDataLink

	@Override
	public void sendTc(PreparedCommand pc) {
		String reason;

		byte[] binary = cmdPostProcessor.process(pc);
		if (binary != null) {

			try {
				sendBuffer(binary);
				dataOutCount.getAndIncrement();
				ackCommand(pc.getCommandId());
				return;
			} catch (IOException e) {
				reason = String.format("Error writing to TC socket to %s:%d; %s", host, port, e.toString());
				log.warn(reason);
			}

		} else {
			reason = "Command postprocessor did not process the command";
		}

		failedCommand(pc.getCommandId(), reason);
	}

	@Override
	public void setCommandHistoryPublisher(CommandHistoryPublisher commandHistoryListener) {
		this.commandHistoryPublisher = commandHistoryListener;
		cmdPostProcessor.setCommandHistoryPublisher(commandHistoryListener);
	}

	/** Send to command history the failed command */
	protected void failedCommand(CommandId commandId, String reason) {
		log.debug("Failing command {}: {}", commandId, reason);
		long currentTime = getCurrentTime();
		commandHistoryPublisher.publishAck(commandId, AcknowledgeSent, currentTime, AckStatus.NOK, reason);
		commandHistoryPublisher.commandFailed(commandId, currentTime, reason);
	}

	/**
	 * send an ack in the command history that the command has been sent out of the
	 * link
	 * 
	 * @param commandId
	 */
	protected void ackCommand(CommandId commandId) {
		commandHistoryPublisher.publishAck(commandId, AcknowledgeSent, getCurrentTime(), AckStatus.OK);
	}

}
