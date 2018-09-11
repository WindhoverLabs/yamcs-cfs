package com.windhoverlabs.yamcs_cfs.tctm;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yamcs.ConfigurationException;
import org.yamcs.YConfiguration;
import org.yamcs.YamcsServer;
import org.yamcs.api.EventProducer;
import org.yamcs.api.EventProducerFactory;
import org.yamcs.archive.PacketWithTime;
import org.yamcs.parameter.SystemParametersCollector;
import org.yamcs.parameter.SystemParametersProducer;
import org.yamcs.protobuf.Pvalue.ParameterValue;
import org.yamcs.protobuf.Yamcs.NamedObjectId;
import org.yamcs.tctm.TmPacketDataLink;
import org.yamcs.tctm.TmSink;
import org.yamcs.time.TimeService;

import com.google.common.util.concurrent.AbstractExecutionThreadService;
import com.odysseysr.yamcs_tmtf.TMTFReader;

public class CfsUdpTmProvider extends AbstractExecutionThreadService implements TmPacketDataLink, SystemParametersProducer {

    /* Enumeration for timestamp format. */
    protected enum CfeTimeStampFormat {
        CFE_SB_TIME_32_16_SUBS,
        CFE_SB_TIME_32_32_SUBS,
        CFE_SB_TIME_32_32_M_20
    }
    /* Enumeration for endianness. */
    protected enum endiannessType {
        LITTLE_ENDIAN,
        BIG_ENDIAN
    }

    protected volatile long packetcount = 0;
    protected DatagramSocket tmSocket;
    protected String host="localhost";
    protected int port=10031;
    protected static CfeTimeStampFormat timestampFormat = CfeTimeStampFormat.CFE_SB_TIME_32_32_SUBS;
    protected static endiannessType endianness = endiannessType.LITTLE_ENDIAN;
    protected int timestampLength = 8;
    protected volatile boolean disabled=false;
    protected EventProducer eventProducer;
    protected int eventMsgID = 0x0808;
    protected boolean deframeTMTFMessages = true;
    protected Logger log=LoggerFactory.getLogger(this.getClass().getName());
    private TmSink tmSink;
    private int CFE_SB_TLM_HDR_SIZE = 6;
    private int OS_MAX_API_NAME = 20;
    private int CFE_EVS_MAX_MESSAGE_LENGTH = 122;
    private short gndSystemApid = 0;
    private ArrayList<byte[]> packetArray = new ArrayList<byte[]>();

    private TMTFReader tmtfReader = new TMTFReader();
    private int CFE_EVS_DEBUG_BIT       = 0x0001;
    private int CFE_EVS_INFORMATION_BIT = 0x0002;
    private int CFE_EVS_ERROR_BIT       = 0x0004;
    private int CFE_EVS_CRITICAL_BIT    = 0x0008;
    
    private SystemParametersCollector sysParamCollector;
    ParameterValue svConnectionStatus;
    List<ParameterValue> sysVariables= new ArrayList<ParameterValue>();
    private String sv_linkStatus_id, sp_dataCount_id;
    final String yamcsInstance;
    final String name;
    final TimeService timeService;

    /* Dummy constructor needed by subclass constructors. */
    protected CfsUdpTmProvider(String instance, String name) {
        this.yamcsInstance = instance;
        this.name = name;
        this.timeService = YamcsServer.getTimeService(instance);

        eventProducer = EventProducerFactory.getEventProducer(this.yamcsInstance);
        eventProducer.setSource("CFS");
    }

    /* Constructor. */
    public CfsUdpTmProvider(String instance, String name, String spec) throws ConfigurationException {
        this.yamcsInstance = instance;
        this.name = name;
        this.timeService = YamcsServer.getTimeService(instance);
        eventProducer = EventProducerFactory.getEventProducer(this.yamcsInstance);

        /* Load the cfs.yaml configuration file. */
        YConfiguration c = YConfiguration.getConfiguration("cfs");
        /* Get the timestamp format as a string. */
        String strTimestampFormat = c.getString(spec, "timestampFormat");
        /* Get the endianness as a string. */
        String strEndianness = c.getString(spec, "endianness");
        /* Get the host IP. */
        host = c.getString(spec, "tmHost");
        /* Get the telemetry port. */
        port = c.getInt(spec, "tmPort");
        /* Print the telemetry port. */
        System.out.println(port+"");
        /* Get the value for CFS OS max app name length. */
        OS_MAX_API_NAME = c.getInt(spec, "OS_MAX_API_NAME");
        /* Get the ground system APID. */
        gndSystemApid = (short)c.getInt(spec, "gndSysApid");

        /* Get framing enabled/disabled. */
        deframeTMTFMessages = c.getBoolean(spec,"framingEnabled");
        /* Get max message length. */
        TMTFReader.MAX_MESSAGE_LENGTH = c.getInt(spec,"framingMaxMessageLength");
        /* Frame Error Control Field (FECF) flag.  */
        TMTFReader.FECF_FLAG = c.getBoolean(spec,"FECF_flag");
        /* Frame Error Control Field (FECF) length.  */
        TMTFReader.FECF_LENGTH = c.getInt(spec,"FECF_length");
        /* Header start position. */
        TMTFReader.TMTF_HEADER_START = c.getInt(spec,"TMTFHeaderStart");
        /* Operational control field length. */
        TMTFReader.OCF_LENGTH = c.getInt(spec,"OCF_length");
        /* CCSDS header length. */
        TMTFReader.CCSDS_HEADER_LENGTH = c.getInt(spec,"CCSDSHeaderLength");
        /* TMTF header length. */
        TMTFReader.TMTF_HEADER_LENGTH = c.getInt(spec,"TMTFHeaderLength");

        /* Decode timestamp format. */
        if(strTimestampFormat.equals("CFE_SB_TIME_32_16_SUBS")) {
            this.timestampFormat = CfeTimeStampFormat.CFE_SB_TIME_32_16_SUBS;
            this.timestampLength = 6;
        } else if(strTimestampFormat.equals("CFE_SB_TIME_32_32_SUBS")) {
            this.timestampFormat = CfeTimeStampFormat.CFE_SB_TIME_32_32_SUBS;
            this.timestampLength = 8;
        } else if(strTimestampFormat.equals("CFE_SB_TIME_32_32_M_20")) {
            this.timestampFormat = CfeTimeStampFormat.CFE_SB_TIME_32_32_M_20;
            this.timestampLength = 8;
        } else {        
            log.warn("timeStampformat not defined or is incorrect, using the default value CFE_SB_TIME_32_32_SUBS");
            this.timestampFormat = CfeTimeStampFormat.CFE_SB_TIME_32_32_SUBS;
            this.timestampLength = 8;
        }

        /* Decode endianness. */
        if(strEndianness.equals("LITTLE_ENDIAN")) {
            this.endianness = endiannessType.LITTLE_ENDIAN;
        } else if(strEndianness.equals("BIG_ENDIAN")) {
            this.endianness = endiannessType.BIG_ENDIAN;
        } else {
            log.warn("endianness not defined or is incorrect, using the default value LITTLE_ENDIAN");
            this.endianness = endiannessType.LITTLE_ENDIAN;
        }
    }

    /* Getter for time stamp format configuration. */
    public static CfeTimeStampFormat getTimeStampFormat()
    {
        return timestampFormat;
    }

    /* Getter for endianness configuration. */
    public static endiannessType getEndianness()
    {
        return endianness;
    }

    /* Determines if a message is an CFE EVS event message. */
    public boolean isEventMsg(byte rawPacket[]) {
        ByteBuffer bb = ByteBuffer.wrap(rawPacket);
        int msgID = bb.getShort();
        /* Partitions 2-4 add 0x0200, 0x0400, or 0x0600 */
        if(msgID == eventMsgID 
           || msgID == eventMsgID + (0x0200) 
           || msgID == eventMsgID + (0x0400) 
           || msgID == eventMsgID + (0x0600) ) {
            return true;
        }

        return false;
    }

    /*
     * 
     * 
    typedef struct 
    {
        uint8   StreamId[2];  /* packet identifier word (stream ID) 
              /*  bits  shift   ------------ description ---------------- 
              /* 0x07FF    0  : application ID                            
              /* 0x0800   11  : secondary header: 0 = absent, 1 = present 
              /* 0x1000   12  : packet type:      0 = TLM, 1 = CMD        
              /* 0xE000   13  : CCSDS version, always set to 0            
    
           uint8   Sequence[2];  /* packet sequence word */
              /*  bits  shift   ------------ description ---------------- 
              /* 0x3FFF    0  : sequence count                            
              /* 0xC000   14  : segmentation flags:  3 = complete packet  

        uint8  Length[2];     /* packet length word */
            /*  bits  shift   ------------ description ---------------- 
            /* 0xFFFF    0  : (total packet length) - 7                 

        
           uint8   Time[CCSDS_TIME_SIZE];
   
        uint8   TlmHeader[CFE_SB_TLM_HDR_SIZE];
           char    AppName[OS_MAX_API_NAME];
           uint16  EventID;                
           uint16  EventType;
           uint32  SpacecraftID;
           uint32  ProcessorID;                          
        char    Message[CFE_EVS_MAX_MESSAGE_LENGTH]; 
        uint8   Spare1;                               
        uint8   Spare2;                              
    } CFE_EVS_Packet_t;
    */

    /* Process an event message for the event viewer. */
    public void ProcessEventMsg(byte rawPacket[]) {
        ByteBuffer bb = ByteBuffer.wrap(rawPacket);

        bb.position(6);
        /* Check target endianness configuration. */
        if(endianness == endiannessType.LITTLE_ENDIAN) {
            /* Set byte order as little endian. */
            bb.order(ByteOrder.LITTLE_ENDIAN);
        } else if(endianness == endiannessType.BIG_ENDIAN) {
            /* Set byte order as big endian. */
            bb.order(ByteOrder.BIG_ENDIAN);
        } else
        {
            bb.order(ByteOrder.LITTLE_ENDIAN);
        }

        long coarseTime = 0;
        long fineTime = 0;

        /* Decode time format. */
        switch(timestampFormat) {
            case CFE_SB_TIME_32_16_SUBS: {
                coarseTime = bb.getInt();
                fineTime = bb.getShort();
                break;
            }
            case CFE_SB_TIME_32_32_SUBS: {
                coarseTime = bb.getInt();
                fineTime = bb.getInt();
                break;
            }
            case CFE_SB_TIME_32_32_M_20: {
                coarseTime = bb.getInt();
                fineTime = bb.getInt();
                break;
            }
        }

        bb.position(bb.position());
        
        byte bAppName[] = new byte[OS_MAX_API_NAME];
        bb.get(bAppName);
        try {
            String appName = new String(bAppName, "ASCII");

            int eventID = bb.getShort();
            int eventType = bb.getShort();
            long spacecraftID = bb.getInt();
            long processorID = bb.getInt();
            String message = "";
            if(bb.remaining() >= CFE_EVS_MAX_MESSAGE_LENGTH) {
                byte bMessage[] = new byte[CFE_EVS_MAX_MESSAGE_LENGTH];
                bb.get(bMessage);
                message = "\n" + new String(bMessage, "ASCII");
            }

            eventProducer.setSource("Airliner/" + spacecraftID + "/" + processorID + "/" + appName);
            if((eventType & CFE_EVS_DEBUG_BIT) != 0) {
                eventProducer.sendInfo("DEBUG", "" + eventID + " / " + message);
            }
            if((eventType & CFE_EVS_INFORMATION_BIT) != 0) {
                eventProducer.sendInfo("INFO", "" + eventID + " / " + message);
            }
            if((eventType & CFE_EVS_ERROR_BIT) != 0) {
                eventProducer.sendInfo("ERROR", "" + eventID + " / " + message);
            }
            if((eventType & CFE_EVS_CRITICAL_BIT) != 0) {
                eventProducer.sendInfo("CRITICAL", "" + eventID + " / " + message);
            }
        } catch (UnsupportedEncodingException e) {
            /* TODO Auto-generated catch block */
            e.printStackTrace();
        }
    }

    protected void openSocket() throws IOException {
        tmSocket = new DatagramSocket(port);
    }

    public void setTmSink(TmSink tmSink) {
        this.tmSink = tmSink;
    }
    
    public void sendCurrentStatus() {
        ByteBuffer bb = ByteBuffer.allocate(1000);
        
        /* Set endian to big endian for the CCSDS header. */
        bb.order(ByteOrder.BIG_ENDIAN);
        /* Packet ID */
        bb.putInt(gndSystemApid);
        
        /* Message ID */
        bb.putShort((short)(0x0800 | gndSystemApid));
        
        /* Sequence */
        bb.putShort((short)0x0000);
        
        /* Secondary telemetry header. */
        /* Course time. */
        bb.putInt(0);
        /* Fine time. */
        bb.putShort((short)0);
        
        /* Set endian back to little endian for the payload */
        bb.order(ByteOrder.LITTLE_ENDIAN);
        
        /* First, store the vehicle address. */
        byte[] vehicleAddress = host.getBytes();
        int length = vehicleAddress.length;
        for(int i = 0; i < 255; ++i) {
            if(i < length) {
                bb.put((byte)vehicleAddress[i]);
            } else {
                bb.put((byte)0);
            }
        }
        bb.put((byte)0);
        /* Vehicle telemetry port. */
        bb.putInt(port);
        PacketWithTime pkt = new PacketWithTime(timeService.getMissionTime(), CfsTlmPacket.getInstant(bb), bb.array());
        tmSink.processPacket(pkt);
    }

    public void run() {
        setupSysVariables();
        sendCurrentStatus();
        while(isRunning()) {
            //try {
            //    Thread.sleep(1000);
            //    sendCurrentStatus();
            //}
            //catch(InterruptedException e)
            //{
            //    log.info("Thread.sleep or sendCurrentStatus failed.");
            //}
            PacketWithTime pwrt=getNextPacket();
            if(pwrt==null) break;
            tmSink.processPacket(pwrt);
        }
    }

    public void refreshPacketArray() throws IOException {
        byte rawFrame[] = new byte[65535];
        int length;
        length = readWithBlocking(rawFrame,0,65535);
        ArrayList<byte[]> containedPackets = new ArrayList<byte[]>();
        try {
            containedPackets = tmtfReader.deframeFrame(rawFrame, length);
            /* Sign that the TMTFReader is out of sync with the messages. New one needed. */
            if (containedPackets == null) {
                log.warn("Skip in VC frame count detected. Frames have been lost. All partial data discarded.");
                tmtfReader = new TMTFReader();
                containedPackets = tmtfReader.deframeFrame(rawFrame, length);
                /* if it is still null, the frame is invalid and needs to be skipped */
            }
        } catch (Exception e) {
            /* This is a bad programming practice but it is highly probable that
               an error may occur here. That does not deserve the death of the 
               stack but rather simply another try next time. */
            e.printStackTrace();
        }
        if (containedPackets != null) {
            packetArray.addAll(containedPackets);
        }
    }

    public PacketWithTime getNextPacket() {
        ByteBuffer bb = null;
        int bytesReceived = 0;

        while (isRunning()) {
            bytesReceived = 0;
            while(disabled) {
                if(!isRunning()) return null;
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
            try {
                if (tmSocket==null) {
                    openSocket();
                    log.info("TM connection established to "+host+" port "+port);
                } 
                byte rawPacket[] = new byte[65535];
                /* If framing is enabled. */
                if (this.deframeTMTFMessages) {
                    if (!(packetArray.size()>0)) {
                        refreshPacketArray();
                        if (!(packetArray.size()>0)) {
                            continue;
                        }
                    }
                    rawPacket = packetArray.get(0); 
                    bytesReceived = packetArray.get(0).length;
                    packetArray.remove(0);
                }else {
                    /* Framing is disabled. */
                    bytesReceived = readWithBlocking(rawPacket,0,65535);
                }

                if(bytesReceived <= 0)
                {
                    continue;
                }

                rawPacket = Arrays.copyOf(rawPacket, bytesReceived);
                /* If the packet is an event message. */
                if(isEventMsg(rawPacket)) {
                    ProcessEventMsg(rawPacket);
                }

                bb=ByteBuffer.allocate(bytesReceived + 4);
                bb.putInt(0);
                bb.put(rawPacket);
                bb.rewind();
                short mask = 0x07ff;
                short apid = (short) (bb.getShort(4) & mask);
                int packetID = (int)apid;
                bb.putInt(packetID);
                bb.rewind();
                packetcount++;
                break;
            } catch (IOException e) {
                log.info("Cannot open or read from TM socket at "+host+":"+port+": "+e+"; retrying in 10 seconds.");
                try {tmSocket.close();} catch (Exception e2) {}
                tmSocket=null;
                try {
                    Thread.sleep(10000);
                } catch (InterruptedException e1) {
                    log.warn("exception "+ e1.toString()+" thrown when sleeping 10 sec");
                    return null;
                }
            }
        }
        if(bb!=null) {
            return new PacketWithTime(timeService.getMissionTime(), CfsTlmPacket.getInstant(bb), bb.array());
        } 
        return null;
    }

    public boolean isArchiveReplay() {
        return false;
    }
    
    /**
     * Read n bytes from the tmSocket, blocking if necessary till all bytes are available.
     * Returns true if all the bytes have been read and false if the stream has closed before all the bytes have been read.
     * @param b
     * @param n
     * @return
     * @throws IOException 
     */
    protected int readWithBlocking(byte[] b, int pos, int n) throws IOException {
        DatagramPacket packet = new DatagramPacket(b, pos, n); //, address);
        tmSocket.receive(packet);
        return packet.getLength();
    }

    public String getLinkStatus() {
        if (disabled) return "DISABLED";
        if (tmSocket==null) {
            return "UNAVAIL";
        } else {
            return "OK";
        }
    }

    @Override
    public void triggerShutdown() {
        if(tmSocket!=null) {
            tmSocket.close();
            tmSocket=null;
        }
    }

    public void disable() {
        disabled=true;
        if(tmSocket!=null) {
            tmSocket.close();
            tmSocket=null;
        }
    }

    public void enable() {
        disabled=false;
    }

    public boolean isDisabled() {
        return disabled;
    }

    public String getDetailedStatus() {
        if(disabled) {
            return String.format("DISABLED (should connect to %s:%d)", host, port);
        }
        if (tmSocket==null) {
            return String.format("Not connected to %s:%d", host, port);
        } else {
            return String.format("OK, connected to %s:%d, received %d packets", host, port, packetcount);
        }
    }


    public long getDataCount() {
        return packetcount;
    }

    protected void setupSysVariables() {
        this.sysParamCollector = SystemParametersCollector.getInstance(yamcsInstance);
        if(sysParamCollector!=null) {
            sysParamCollector.registerProvider(this, null);
            sv_linkStatus_id = sysParamCollector.getNamespace()+"/"+name+"/linkStatus";
            sp_dataCount_id = sysParamCollector.getNamespace()+"/"+name+"/dataCount";


        } else {
            log.info("System variables collector not defined for instance {} ", yamcsInstance);
        }
    }

    public Collection<org.yamcs.parameter.ParameterValue> getSystemParameters() {
        long time = timeService.getMissionTime();
        org.yamcs.parameter.ParameterValue linkStatus = SystemParametersCollector.getPV(sv_linkStatus_id, time, getLinkStatus());
        org.yamcs.parameter.ParameterValue dataCount = SystemParametersCollector.getPV(sp_dataCount_id, time, getDataCount());
        return Arrays.asList(linkStatus, dataCount);
    }
}

