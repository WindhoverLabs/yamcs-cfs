package com.windhoverlabs.yamcs.tctm;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.WritableByteChannel;
import java.util.concurrent.atomic.AtomicLong;

import org.yamcs.ConfigurationException;
import org.yamcs.TmPacket;
import org.yamcs.YConfiguration;
import org.yamcs.cmdhistory.CommandHistoryPublisher;
import org.yamcs.cmdhistory.CommandHistoryPublisher.AckStatus;

import static org.yamcs.cmdhistory.CommandHistoryPublisher.AcknowledgeSent;
import org.yamcs.commanding.PreparedCommand;
import org.yamcs.protobuf.Commanding.CommandId;
import org.yamcs.tctm.AggregatedDataLink;
import org.yamcs.tctm.CommandPostprocessor;
import org.yamcs.tctm.GenericCommandPostprocessor;
import org.yamcs.tctm.TcDataLink;
import org.yamcs.utils.TimeEncoding;
import org.yamcs.utils.YObjectLoader;

public class SerialTmTcDatalink extends SerialTmDatalink implements TcDataLink, Runnable {
    protected CommandPostprocessor cmdPostProcessor;
    protected CommandHistoryPublisher commandHistoryPublisher;
    protected AtomicLong dataOutCount = new AtomicLong();
    private AggregatedDataLink parent = null;
    
	OutputStream outputStream = null;
	
    @Override
    public void init(String instance, String name, YConfiguration config) throws ConfigurationException {
        // Read arguments
        super.init(instance, name, config);

        // Setup tc postprocessor
        initPostprocessor(yamcsInstance, config);
    }
    
	@Override
    protected void openDevice() throws IOException {
		super.openDevice();
        this.outputStream = serialPort.getOutputStream();
	}
	

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
    protected long getCurrentTime() {
        if (timeService != null) {
            return timeService.getMissionTime();
        }
        return TimeEncoding.getWallclockTime();
    }
    
	
	@Override
	public void sendTc(PreparedCommand pc) {
        byte[] binary = cmdPostProcessor.process(pc);
        if (binary == null) {
            log.warn("command postprocessor did not process the command");
            return;
        }
        
        int retries = 5;
        boolean sent = false;

        ByteBuffer bb = ByteBuffer.wrap(binary);
        bb.rewind();
        String reason = null;
        while (!sent && (retries > 0)) {
            try {
            	if(serialPort == null) {
            	    openDevice();
            	}
            	WritableByteChannel channel = Channels.newChannel(outputStream);
            	channel.write(bb);
            	dataOutCount.getAndIncrement();
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
     * send an ack in the command history that the command has been sent out of the link
     * 
     * @param commandId
     */
    protected void ackCommand(CommandId commandId) {
        commandHistoryPublisher.publishAck(commandId, AcknowledgeSent, getCurrentTime(), AckStatus.OK);
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
            if (tmpkt == null) {
                break;
            }
            processPacket(tmpkt);
        }
	}
}
