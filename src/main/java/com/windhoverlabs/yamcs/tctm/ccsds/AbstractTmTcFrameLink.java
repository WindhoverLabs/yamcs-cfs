package com.windhoverlabs.yamcs.tctm.ccsds;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.yamcs.ConfigurationException;
import org.yamcs.YConfiguration;
import org.yamcs.cmdhistory.CommandHistoryPublisher;
import org.yamcs.cmdhistory.CommandHistoryPublisher.AckStatus;
import org.yamcs.commanding.PreparedCommand;
import org.yamcs.tctm.AbstractLink;
import org.yamcs.tctm.AggregatedDataLink;
import org.yamcs.tctm.Link;
import org.yamcs.tctm.TcDataLink;
import org.yamcs.tctm.RawFrameDecoder;
import org.yamcs.tctm.TcTmException;
import org.yamcs.time.Instant;

import org.yamcs.tctm.ccsds.MasterChannelFrameMultiplexer;
import org.yamcs.tctm.ccsds.MasterChannelFrameHandler;
import org.yamcs.tctm.ccsds.TcTransferFrame;
import org.yamcs.tctm.ccsds.VcUplinkHandler;
import org.yamcs.tctm.ccsds.VcDownlinkHandler;
import org.yamcs.tctm.ccsds.CcsdsFrameDecoder;

import org.yamcs.tctm.ccsds.error.BchCltuGenerator;
import org.yamcs.tctm.ccsds.error.CltuGenerator;
import org.yamcs.tctm.ccsds.error.Ldpc256CltuGenerator;
import org.yamcs.tctm.ccsds.error.Ldpc64CltuGenerator;
import org.yamcs.utils.IntArray;
import org.yamcs.utils.TimeEncoding;
import org.yamcs.utils.YObjectLoader;

/**
 * Sends TC as TC frames (CCSDS 232.0-B-3) or TC frames embedded in CLTU (CCSDS 231.0-B-3).
 * 
 * 
 * @author nm
 *
 */
public abstract class AbstractTmTcFrameLink extends AbstractLink implements AggregatedDataLink, TcDataLink {
    protected AtomicLong tcFrameCount = new AtomicLong(0);
    protected AtomicLong tmFrameCount = new AtomicLong(0);
    boolean sendCltu;
    protected MasterChannelFrameMultiplexer multiplexer;
    List<Link> tcSubLinks;
    List<Link> tmSubLinks;
    boolean randomize;
    protected RawFrameDecoder rawFrameDecoder;
    protected MasterChannelFrameHandler frameHandler;

    // do not randomize the virtual channels from this array
    IntArray skipRandomizationForVcs = null;

    protected CommandHistoryPublisher commandHistoryPublisher;
    protected CltuGenerator cltuGenerator;
    final static String CLTU_START_SEQ_KEY = "cltuStartSequence";
    final static String CLTU_TAIL_SEQ_KEY = "cltuTailSequence";

    public void init(String yamcsInstance, String linkName, YConfiguration config) {
        super.init(yamcsInstance, linkName, config);
        YConfiguration tcConfig = config.getConfig("tc_config");
        YConfiguration tmConfig = config.getConfig("tm_config");
        int dfl = -1;
        
        /* Configure telemetry. */        
        if (tmConfig.containsKey("rawFrameDecoder")) {
            YConfiguration rconfig = config.getConfig("rawFrameDecoder");
            rawFrameDecoder = new CcsdsFrameDecoder(rconfig);
            dfl = rawFrameDecoder.decodedFrameLength();
        }

        frameHandler = new MasterChannelFrameHandler(yamcsInstance, linkName, tmConfig);

        if (dfl != -1) {
            int mindfl = frameHandler.getMinFrameSize();
            int maxdfl = frameHandler.getMaxFrameSize();
            if (dfl < mindfl || dfl > maxdfl) {
                throw new ConfigurationException("Raw frame decoder output frame length " + dfl +
                        " does not match the defined frame length "
                        + (mindfl == maxdfl ? Integer.toString(mindfl) : "[" + mindfl + ", " + maxdfl + "]"));
            }
        }

        tmSubLinks = new ArrayList<>();
        for (VcDownlinkHandler vch : frameHandler.getVcHandlers()) {
            if (vch instanceof Link) {
                Link l = (Link) vch;
                tmSubLinks.add(l);
                l.setParent(this);
            }
        }
        
        /* Configure commanding. */
        if (tcConfig.containsKey("skipRandomizationForVcs")) {
            List<Integer> l = tcConfig.getList("skipRandomizationForVcs");
            if (!l.isEmpty()) {
                int[] a = l.stream().mapToInt(i -> i).toArray();
                skipRandomizationForVcs = IntArray.wrap(a);
                skipRandomizationForVcs.sort();
            }
        }
        String cltuEncoding = tcConfig.getString("cltuEncoding", null);
        if (cltuEncoding != null) {
            if ("BCH".equals(cltuEncoding)) {
                byte[] startSeq = tcConfig.getBinary(CLTU_START_SEQ_KEY, BchCltuGenerator.CCSDS_START_SEQ);
                byte[] tailSeq = tcConfig.getBinary(CLTU_TAIL_SEQ_KEY, BchCltuGenerator.CCSDS_TAIL_SEQ);
                this.randomize = tcConfig.getBoolean("randomizeCltu", false);
                cltuGenerator = new BchCltuGenerator(startSeq, tailSeq);
            } else if ("LDPC64".equals(cltuEncoding)) {
                checkSuperfluosLdpcRandomizationOption(tcConfig);
                byte[] startSeq = tcConfig.getBinary(CLTU_START_SEQ_KEY, Ldpc64CltuGenerator.CCSDS_START_SEQ);
                byte[] tailSeq = tcConfig.getBinary(CLTU_TAIL_SEQ_KEY, CltuGenerator.EMPTY_SEQ);
                cltuGenerator = new Ldpc64CltuGenerator(startSeq, tailSeq);
                this.randomize = true;
            } else if ("LDPC256".equals(cltuEncoding)) {
                checkSuperfluosLdpcRandomizationOption(tcConfig);
                byte[] startSeq = tcConfig.getBinary(CLTU_START_SEQ_KEY, Ldpc256CltuGenerator.CCSDS_START_SEQ);
                byte[] tailSeq = tcConfig.getBinary(CLTU_TAIL_SEQ_KEY, CltuGenerator.EMPTY_SEQ);
                cltuGenerator = new Ldpc256CltuGenerator(startSeq, tailSeq);
                this.randomize = true;
            } else if ("CUSTOM".equals(cltuEncoding)) {
            	String cltuGeneratorClassName = tcConfig.getString("cltuGeneratorClassName", null);
            	if (cltuGeneratorClassName == null) {
            		throw new ConfigurationException("CUSTOM cltu generator requires value for cltuGeneratorClassName");
            	}
            	if (!tcConfig.containsKey("cltuGeneratorArgs")) {
            		cltuGenerator = YObjectLoader.loadObject(cltuGeneratorClassName);
            	} else {
            		YConfiguration args = tcConfig.getConfig("cltuGeneratorArgs");
            		cltuGenerator = YObjectLoader.loadObject(cltuGeneratorClassName, args);
            	}
                this.randomize = tcConfig.getBoolean("randomizeCltu", false);
            } else {
                throw new ConfigurationException(
                        "Invalid value '" + cltuEncoding + " for cltu. Valid values are BCH, LDPC64, LDPC256, or CUSTOM");
            }
        }

        multiplexer = new MasterChannelFrameMultiplexer(yamcsInstance, linkName, tcConfig);
        tcSubLinks = new ArrayList<>();
        for (VcUplinkHandler vch : multiplexer.getVcHandlers()) {
            if (vch instanceof Link) {
                Link l = (Link) vch;
                tcSubLinks.add(l);
                l.setParent(this);
            }
        }
    }

    static void checkSuperfluosLdpcRandomizationOption(YConfiguration config) {
        if (!config.getBoolean("randomizeCltu", true)) {
            throw new ConfigurationException(
                    "CLTU randomization is always enabled for the LDPC codec, please remove the randomizeCltu option");
        }
    }

    /**
     * optionally encode the data to CLTU if the CLTU generator is configured.
     * <p>
     * Randomization will also be performed if configured.
     */
    protected byte[] encodeCltu(int vcId, byte[] data) {
        if (cltuGenerator != null) {
            boolean rand = randomize
                    && (skipRandomizationForVcs == null || skipRandomizationForVcs.binarySearch(vcId) < 0);
            return cltuGenerator.makeCltu(data, rand);
        } else {
            return data;
        }

    }

    @Override
    public long getDataInCount() {
        return tmFrameCount.get();
    }

    @Override
    public long getDataOutCount() {
        return tcFrameCount.get();
    }

    @Override
    public void resetCounters() {
    	tcFrameCount.set(0);
    }

    @Override
    public List<Link> getSubLinks() {
//        List<Link> subLinks = new ArrayList<Link>();
//        subLinks.addAll(tcSubLinks);
//        subLinks.addAll(tmSubLinks);
//
//        return subLinks;
        
        return tcSubLinks;
    }

    @Override
    public void setCommandHistoryPublisher(CommandHistoryPublisher commandHistoryPublisher) {
        this.commandHistoryPublisher = commandHistoryPublisher;
    }

    @Override
    public boolean sendCommand(PreparedCommand preparedCommand) {
      throw new ConfigurationException(
          "This class cannot send command directly, please remove the stream associated to the main link");
    }

    /**
     * Ack the BD frames
     * Note: the AD frames are acknowledged in the when the COP1 ack is received
     * 
     * @param tf
     */
    protected void ackBypassFrame(TcTransferFrame tf) {
        if (tf.getCommands() != null) {
            for (PreparedCommand pc : tf.getCommands()) {
                commandHistoryPublisher.publishAck(pc.getCommandId(), CommandHistoryPublisher.AcknowledgeSent,
                        timeService.getMissionTime(), AckStatus.OK);
            }
        }
    }

    protected void failBypassFrame(TcTransferFrame tf, String reason) {
        if (tf.getCommands() != null) {
            for (PreparedCommand pc : tf.getCommands()) {
                commandHistoryPublisher.publishAck(pc.getCommandId(), CommandHistoryPublisher.AcknowledgeSent,
                        TimeEncoding.getWallclockTime(), AckStatus.NOK, reason);

                commandHistoryPublisher.commandFailed(pc.getCommandId(), timeService.getMissionTime(), reason);
            }
        }
    }

}
