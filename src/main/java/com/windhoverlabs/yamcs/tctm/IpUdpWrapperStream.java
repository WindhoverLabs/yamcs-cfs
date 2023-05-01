package com.windhoverlabs.yamcs.tctm;

import com.google.common.util.concurrent.RateLimiter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.yamcs.AbstractYamcsService;
import org.yamcs.ConfigurationException;
import org.yamcs.InitException;
import org.yamcs.Spec;
import org.yamcs.TmPacket;
import org.yamcs.YConfiguration;
import org.yamcs.YamcsServer;
import org.yamcs.events.EventProducer;
import org.yamcs.events.EventProducerFactory;
import org.yamcs.logging.Log;
import org.yamcs.parameter.SystemParametersProducer;
import org.yamcs.tctm.Link;
import org.yamcs.time.SimulationTimeService;
import org.yamcs.time.TimeService;
import org.yamcs.utils.DataRateMeter;
import org.yamcs.utils.StringConverter;
import org.yamcs.yarch.ColumnDefinition;
import org.yamcs.yarch.DataType;
import org.yamcs.yarch.Stream;
import org.yamcs.yarch.StreamSubscriber;
import org.yamcs.yarch.Tuple;
import org.yamcs.yarch.TupleDefinition;
import org.yamcs.yarch.YarchDatabase;
import org.yamcs.yarch.YarchDatabaseInstance;
import org.yamcs.events.EventProducer;
import org.yamcs.events.EventProducerFactory;
import java.math.BigInteger;

/**
 * Receives telemetry fames via UDP. One UDP datagram = one TM frame.
 *
 * @author nm
 */
public class IpUdpWrapperStream extends AbstractYamcsService
    implements Link, StreamSubscriber, SystemParametersProducer {
  RateLimiter outRateLimiter;
  protected YConfiguration config;
  protected String linkName;
  protected AtomicBoolean disabled = new AtomicBoolean(false);
  protected Log log;
  protected EventProducer eventProducer;
  protected TimeService timeService;
  protected AtomicLong inPacketCount = new AtomicLong(0);
  protected AtomicLong outPacketCount = new AtomicLong(0);
  protected boolean updateSimulationTime;
  DataRateMeter inPacketRateMeter = new DataRateMeter();
  DataRateMeter outPacketRateMeter = new DataRateMeter();
  DataRateMeter inDataRateMeter = new DataRateMeter();
  DataRateMeter outDataRateMeter = new DataRateMeter();
  protected PacketPreprocessor packetPreprocessor;
  protected Stream inStream;
  protected Stream outStream;
  int ipIdentification;
  String srcAddress = "192.168.1.55";
  String dstAddress = "192.168.3.2";
  int    srcPort = 42001;
  int    dstPort = 42000;
  int    ttl = 64;

  // FIXME:Temporary. Don't want to be exposing this packet so easily.
  private byte[] packet;

  static TupleDefinition gftdef;

  static final String RECTIME_CNAME = "rectime";
  static final String DATA_CNAME = "data";

  static {
    gftdef = new TupleDefinition();
    gftdef.addColumn(new ColumnDefinition(RECTIME_CNAME, DataType.TIMESTAMP));
    gftdef.addColumn(new ColumnDefinition(DATA_CNAME, DataType.BINARY));
  }

  /**
   * Creates a new UDP Frame Data Link
   *
   * @throws ConfigurationException if port is not defined in the configuration
   */
  public void init(String instance, String name, YConfiguration config) {
    try {
      super.init(instance, name, config);
    } catch (InitException e1) {
      // TODO Auto-generated catch block
      e1.printStackTrace();
    }

    this.ipIdentification = 1;
    this.linkName = name;
    this.config = config;
    log = new Log(getClass(), instance);
    log.setContext(name);
    eventProducer = EventProducerFactory.getEventProducer(instance, name, 10000);
    this.timeService = YamcsServer.getTimeService(instance);
    String inStreamName = config.getString("in_stream");
    String outStreamName = config.getString("out_stream");

    srcAddress = config.getString("srcAddress", "127.0.0.1");
    dstAddress = config.getString("dstAddress", "127.0.0.1");
    srcPort = config.getInt("srcPort");
    if(srcPort > 0xffff) {
        throw new ConfigurationException(
            "Source Port must be less than 65536");
    }
    dstPort = config.getInt("dstPort");
    if(dstPort > 0xffff) {
        throw new ConfigurationException(
            "Destination Port must be less than 65536");
    }
    ttl = config.getInt("ttl", 64);
    if(ttl > 0xff) {
        throw new ConfigurationException(
            "TTL must be less than 256");
    }

    YarchDatabaseInstance ydb = YarchDatabase.getInstance(instance);
    this.inStream = getStream(ydb, inStreamName);
    this.outStream = getStream(ydb, outStreamName);

    this.inStream.addSubscriber(this);

    if (config.containsKey("frameMaxRate")) {
      outRateLimiter = RateLimiter.create(config.getDouble("frameMaxRate"), 1, TimeUnit.SECONDS);
    }

    updateSimulationTime = config.getBoolean("updateSimulationTime", false);
    if (updateSimulationTime) {
      if (timeService instanceof SimulationTimeService) {
        SimulationTimeService sts = (SimulationTimeService) timeService;
        sts.setTime0(0);
      } else {
        throw new ConfigurationException(
            "updateSimulationTime can only be used together with SimulationTimeService "
                + "(add 'timeService: org.yamcs.time.SimulationTimeService' in yamcs.<instance>.yaml)");
      }
    }
  }

  private static Stream getStream(YarchDatabaseInstance ydb, String streamName) {
    Stream stream = ydb.getStream(streamName);
    if (stream == null) {
      try {
        ydb.execute("create stream " + streamName + gftdef.getStringDefinition());
        // ydb.execute("create stream " + streamName);
      } catch (Exception e) {
        throw new ConfigurationException(e);
      }
      stream = ydb.getStream(streamName);
    }
    return stream;
  }

  @Override
  public YConfiguration getConfig() {
    return config;
  }

  @Override
  public String getName() {
    return linkName;
  }

  @Override
  public void resetCounters() {
    inPacketCount.set(0);
    outPacketCount.set(0);
  }

  @Override
  public void doStart() {
    if (!isDisabled()) {
      // new Thread(this).start();
    }
    notifyStarted();
  }

  @Override
  public void doStop() {
    notifyStopped();
  }

  public boolean isRunningAndEnabled() {
    State state = state();
    return (state == State.RUNNING || state == State.STARTING) && !disabled.get();
  }

  /**
   * Sends the packet downstream for processing.
   *
   * <p>Starting in Yamcs 5.2, if the updateSimulationTime option is set on the link configuration,
   *
   * <ul>
   *   <li>the timeService is expected to be SimulationTimeService
   *   <li>at initialization, the time0 is set to 0
   *   <li>upon each packet received, the generationTime (as set by the pre-processor) is used to
   *       update the simulation elapsed time
   * </ul>
   *
   * <p>Should be called by all sub-classes (instead of directly calling {@link
   * TmSink#processPacket(TmPacket)}
   *
   * @param tmpkt
   */
  protected void processPacket(TmPacket tmpkt) {
    long rectime = tmpkt.getReceptionTime();
    byte byteArray[] = tmpkt.getPacket();

    inStream.emitTuple(new Tuple(gftdef, Arrays.asList(rectime, byteArray)));

    if (updateSimulationTime) {
      SimulationTimeService sts = (SimulationTimeService) timeService;
      if (!tmpkt.isInvalid()) {
        sts.setSimElapsedTime(tmpkt.getGenerationTime());
      }
    }
  }

  /**
   * called when a new packet is received to update the statistics
   *
   * @param packetSize
   */
  protected void updateInStats(int packetSize) {
    inPacketCount.getAndIncrement();
    inPacketRateMeter.mark(1);
    inDataRateMeter.mark(packetSize);
  }

  /**
   * called when a new packet is sent to update the statistics
   *
   * @param packetSize
   */
  protected void updateOutStats(int packetSize) {
    outPacketCount.getAndIncrement();
    outPacketRateMeter.mark(1);
    outDataRateMeter.mark(packetSize);
  }

  @Override
  public void disable() {
    boolean b = disabled.getAndSet(true);
    if (!b) {
      try {
        /* TODO */
        // doDisable();
      } catch (Exception e) {
        disabled.set(false);
        log.warn("Failed to disable link", e);
      }
    }
  }
  
    
    public static byte[] genUdpPacket (byte[] payload, int identification, String srcAddress, String dstAddress, int srcPort, int dstPort, int TTL) {
        /* The total length is the size of the IP header (20 bytes) + UDP header (8 bytes) + payload size */
        int totalLength = 20 + 8 + payload.length;
        byte[] packet = new byte[totalLength];
        
        /* NOTE: The IP protocol numbers bits left to right, not right to left, so the comments
           below are following the same ordering. */
           
        /* Version ID - Word 0 bits 0-3 (byte 0, bits 0-3 ... so shift left 4 */
        /* This is IPv4 so its always 4.  This is NOT configurable. */
        packet[0] = (byte)(4 << 4);
        
        /* Set IHL - Word 0 bits 4-7 (byte 0, bits 4-7 ... so NO shift 
         * This is number of 32 bit words. If the options flag is not set, there is no options
         * field and the header will be 5 32 bit words in size.  We are not including options, so
         * set this to 5. */
        packet[0] = (byte)(packet[0] | 5);
        
        /* Differentiated Services Field.  Word 0 bits 8-15. (byte 1)  We aren't doing any thing 
         * crazy here. Just set this to zero. 
         */
        packet[1] = 0;
        
        /* Total Length - Word 0 bits 16-31 (bytes 2-3)
         * The total length is the size of the IP header (20 bytes) + UDP header (8 bytes) + payload
         */
        packet[2] = getHighByteFromInt(totalLength);
        packet[3] = getLowByteFromInt(totalLength);
        
        /* Identification.  Word 1 bits 0-15 (bytes 4-5)
         */   
        packet[4] = getHighByteFromInt(identification);
        packet[5] = getLowByteFromInt(identification);
        
        /* Flags and fragment offset.  Word 1 bits 16 - 31 (bytes 6-7)
         * We're just going to set the "Don't Fragment" bit set because reasons.  This equates to 
         * 0x4000
         */   
        packet[6] = getHighByteFromInt(0x0000);
        packet[7] = getLowByteFromInt(0x0000);
        
        /* TTL. Word 2 bits 0-7 (byte 8) */
        packet[8] = getLowByteFromInt(TTL);
        
        /* Protocol. Word 2 bits 8-15 (byte 9) 
         * Obviously, we're going to set this to UDP. "UDP" is 17.
         */
        packet[9] = 17;
        
        /* Checksum. Word 2 bits 16-31 (bytes 10-11) 
         * We don't have the packet populated yet, so we can't calculate the checksum just yet. Set 
         * this to zero for now.
         */
        packet[10] = 0;
        packet[11] = 0;
        
        /* Source IP address. Word 3 bits 0-31 (bytes 12-15)
         * First we need to convert the string to a byte array.  Start with splitting the string with "." as
         * the token. Then convert each array element to an integer.
         */
        String[] splitSrcAddress = srcAddress.split("\\.");
        packet[12] = (byte)(Integer.parseInt(splitSrcAddress[0]));
        packet[13] = (byte)(Integer.parseInt(splitSrcAddress[1]));
        packet[14] = (byte)(Integer.parseInt(splitSrcAddress[2]));
        packet[15] = (byte)(Integer.parseInt(splitSrcAddress[3]));
        
        /* Destination IP address. Word 4 bits 0-31 (bytes 16-19)
         * First we need to convert the string to a byte array.  Start with splitting the string with "." as
         * the token. Then convert each array element to an integer.
         */
        String[] splitDstAddress = dstAddress.split("\\.");
        packet[16] = (byte)(Integer.parseInt(splitDstAddress[0]));
        packet[17] = (byte)(Integer.parseInt(splitDstAddress[1]));
        packet[18] = (byte)(Integer.parseInt(splitDstAddress[2]));
        packet[19] = (byte)(Integer.parseInt(splitDstAddress[3]));
        
        /* Now we can populate the checksum. First calculate it. */
        int checksum = 0;
        for(int i = 0; i < 20; i=i+2) {
            int value = convertBytesToInt(packet[i], packet[i+1]);           
            checksum = addWithCarry(checksum, value);
        }
        /* Take the ones complement  */
        checksum = ~checksum;
        /* Store the IP checksum. */
        packet[10] = getHighByteFromInt(checksum);
        packet[11] = getLowByteFromInt(checksum);
        
        /* UDP */
        /* Source Port */
        packet[20] = getHighByteFromInt(srcPort);
        packet[21] = getLowByteFromInt(srcPort);

        /* Destination Port */
        packet[22] = getHighByteFromInt(dstPort);
        packet[23] = getLowByteFromInt(dstPort);

        /* Length */
        int udpLength = 8 + payload.length;
        packet[24] = getHighByteFromInt(udpLength);
        packet[25] = getLowByteFromInt(udpLength);   
        
        /* Store checksum temporarily */
        packet[26] = 0;
        packet[27] = 0;

        /* Copy payload */
        for(int i = 0; i < payload.length; ++i) {
        	packet[28+i] = payload[i];
        }
        
        /* UDP Checksum 
         * Start by clearing the checksum 
         */
        checksum = 0;
        
        /* Now sum the 'pseudo header' starting with the protocol field. */
        checksum = addWithCarry(checksum, 0x0011);
        	
        /* Add in the UDP Length field. */          
        checksum = addWithCarry(checksum, udpLength);
        
        /* Now add in the UDP header and the payload. */
        int i;
        for(i = 12; i < (totalLength-1); i=i+2) {
            int value = convertBytesToInt(packet[i], packet[i+1]);            
            checksum = addWithCarry(checksum, value);
        }
        /* Check for an odd length message.  If the total length is an odd number we have one 
         * last byte to add in.  The spec states that when this happens, to just add the 
         * remaining byte as is.  No padding to make it 16 bits. 
         */
        if(i < totalLength) {
        	checksum = addWithCarry(checksum, packet[i] << 16);
        }
     
        /* Get the ones complement */
        checksum = ~checksum;
        packet[26] = getHighByteFromInt(checksum);
        packet[27] = getLowByteFromInt(checksum);
        
        return packet;
    }
    
    
    public static byte getHighByteFromInt(int intValue) {
        byte result;
        
        /* Get the high byte from the network order integer. */
        result = (byte)((intValue & 0x0000ff00) >> 8);
        
        return result; 
    }
    
    
    public static byte getLowByteFromInt(int intValue) {
        byte result;

        /* Get the low byte from the network order integer. */    
        result = (byte)(intValue &  0x000000ff);  
        
        return result;
    }
    
    
    public static int convertBytesToInt(byte HI, byte LO) {
        int result;
        
        result = ((int)(HI << 8) & 0x0000ff00) | (int)(LO & 0x000000ff);
        
        return result;
    }
    
    
    public static int addWithCarry(int A, int B) {
        int result;

        /* Add the two values together as 32 bit integers.  We need to do this so we 
         * can get the carry digit next.
         */
        int temp = (A & 0x0000ffff) + (B & 0x0000ffff);

        /* Get the carry digit */
        int carry = ((temp & 0x00010000) >>> 16);

        /* Add the carry digit to the summed result. */
        result = (temp + carry) & 0x0000ffff;
        
        return result;
    }
    
    
    public static int addWithCarry(int A, byte B) {
        int result;

        /* Add the two values together as 32 bit integers.  We need to do this so we 
         * can get the carry digit next.
         */
        int temp = (A & 0x0000ffff) + (B & 0x000000ff);

        /* Get the carry digit */
        int carry = ((temp & 0xffff0000) >>> 16);

        /* Add the carry digit to the summed result. */
        result = (temp + carry) & 0x0000ffff;
        
        return result;
    }


  public String getPacket() {
    return StringConverter.arrayToHexString(packet);
  }

  @Override
  public void enable() {
    boolean b = disabled.getAndSet(false);
    if (b) {
      try {
        /* TODO */
        // doEnable();
      } catch (Exception e) {
        disabled.set(true);
        log.warn("Failed to enable link", e);
      }
    }
  }

  @Override
  public long getDataInCount() {
    return inPacketCount.get();
  }

  @Override
  public long getDataOutCount() {
    return outPacketCount.get();
  }

  @Override
  public Status getLinkStatus() {
    if (isDisabled()) {
      return Status.DISABLED;
    }
    if (state() == State.FAILED) {
      return Status.FAILED;
    }

    return connectionStatus();
  }

  @Override
  public boolean isDisabled() {
    return disabled.get();
  }

  protected Status connectionStatus() {
    return Status.OK;
  }

  @Override
  public Spec getSpec() {
    // TODO Auto-generated method stub
    return super.getSpec();
  }

  @Override
  public void onTuple(Stream arg0, Tuple tuple) {
    if (isRunningAndEnabled()) {

      byte[] packet;
       
      this.ipIdentification++;
      packet = genUdpPacket(tuple.getColumn(DATA_CNAME), this.ipIdentification, this.srcAddress, this.dstAddress, this.srcPort, this.dstPort, this.ttl);

      // long recTime = tuple.getColumn(PreparedCommand.CNAME_GENTIME);
      if (packet == null) {
        throw new ConfigurationException("no column named '%s' in the tuple", DATA_CNAME);
      } else {
        outStream.emitTuple(
            new Tuple(gftdef, Arrays.asList(tuple.getColumn(RECTIME_CNAME), packet)));

        updateOutStats(packet.length);
      }
    }
  }
}
