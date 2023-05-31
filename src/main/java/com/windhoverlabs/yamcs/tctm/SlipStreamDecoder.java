package com.windhoverlabs.yamcs.tctm;

import com.google.common.util.concurrent.RateLimiter;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
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

/**
 * Receives telemetry fames via UDP. One UDP datagram = one TM frame.
 *
 * @author nm
 */
public class SlipStreamDecoder extends AbstractYamcsService
    implements Link, StreamSubscriber, SystemParametersProducer {
  private final byte END = (byte) 0xc0;
  private final byte ESC = (byte) 0xdb;
  private final byte ESC_END = (byte) 0xdc;
  private final byte ESC_ESC = (byte) 0xdd;
  String asmString = "1ACFFC1D";
  byte[] asm;
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
  protected boolean hasPreprocessor = false;
  protected Stream inStream;
  protected Stream outStream;
  int rcvdCaduCount = 0;

  int rcvdFatCaduCount = 0;
  boolean dropMalformed = true;
  int asmLength;
  int outOfSyncByteCount = 0;
  int inSyncByteCount = 0;

  enum ParserState {
    OUT_OF_SYNC,
    AT_CADU_START,
    PARSING_CADU,
    PARSING_ASM,
    CADU_COMPLETE
  }

  ParserState parserState;
  int minLength = 0;
  int fixedLength = -1;

  private int asmCursor;
  private int fatFrameBytes;
  private int caduLength;
  // FIXME:Temporary. Don't want to be exposing this packet so easily.
  private byte[] packet;

  private int fatFrameCount = 0;

  static TupleDefinition gftdef;

  static final String RECTIME_CNAME = "rectime";
  static final String DATA_CNAME = "data";

  static {
    gftdef = new TupleDefinition();
    gftdef.addColumn(new ColumnDefinition(RECTIME_CNAME, DataType.TIMESTAMP));
    gftdef.addColumn(new ColumnDefinition(DATA_CNAME, DataType.BINARY));
  }

  static final String CFG_PREPRO_CLASS = "packetPreprocessorClassName";

  static final int MAX_LENGTH = 1500;

  private int offset;
  private int rightTrim;
  int maxLength;

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

    asmString = config.getString("asm", asmString);
    asmLength = asmString.length() / 2;
    minLength = config.getInt("minLength", minLength);
    maxLength = config.getInt("maxLength", maxLength);
    dropMalformed = config.getBoolean("dropMalformed", dropMalformed);
    this.linkName = name;
    this.config = config;
    offset = config.getInt("offset", 0);
    rightTrim = config.getInt("rightTrim", 0);
    maxLength = config.getInt("maxLength", MAX_LENGTH);
    log = new Log(getClass(), instance);
    log.setContext(name);
    eventProducer = EventProducerFactory.getEventProducer(instance, name, 10000);
    this.timeService = YamcsServer.getTimeService(instance);
    String inStreamName = config.getString("in_stream");
    String outStreamName = config.getString("out_stream");

    YarchDatabaseInstance ydb = YarchDatabase.getInstance(instance);
    this.inStream = getStream(ydb, inStreamName);
    this.outStream = getStream(ydb, outStreamName);

    this.inStream.addSubscriber(this);

    if (config.containsKey("frameMaxRate")) {
      outRateLimiter = RateLimiter.create(config.getDouble("frameMaxRate"), 1, TimeUnit.SECONDS);
    }

    if (config.containsKey(CFG_PREPRO_CLASS)) {
      this.hasPreprocessor = true;
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

    if (maxLength < 0) {
      throw new ConfigurationException("'maxLength' must be defined.");
    }

    if (maxLength < minLength) {
      throw new ConfigurationException(
          "'maxLength' (" + maxLength + ") must not be less than 'minLength' (" + minLength + ").");
    }

    if (maxLength < 0) {
      throw new ConfigurationException(
          "'maxLength' (" + maxLength + ") must be greater than zero.");
    }

    if (minLength < 0) {
      throw new ConfigurationException(
          "'minLength' (" + maxLength + ") must be greater than zero.");
    }

    if (dropMalformed && (maxLength < 0)) {
      throw new ConfigurationException(
          "'dropMalformed' must not be 'true' unless 'maxLength' is defined.");
    }

    asm = fromHexString(asmString);

    if (minLength == maxLength) {
      fixedLength = minLength;
    }

    outOfSyncByteCount = 0;
    inSyncByteCount = 0;
    rcvdCaduCount = 0;
    rcvdFatCaduCount = 0;
    parserState = ParserState.OUT_OF_SYNC;
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
  /*
  protected void processPacket(TmPacket tmpkt) {
    long rectime = tmpkt.getReceptionTime();
    byte byteArray[] = tmpkt.getPacket();
    
    int payloadSize = byteArray.length - this.offset - this.rightTrim;

    if(byteArray.length < payloadSize) {
      log.warn("Ignoring partial packet");
    } else {
      byte[] trimmedByteArray =
          Arrays.copyOfRange(byteArray, this.offset, payloadSize);

      inStream.emitTuple(new Tuple(gftdef, Arrays.asList(rectime, trimmedByteArray)));

      if (updateSimulationTime) {
        SimulationTimeService sts = (SimulationTimeService) timeService;
        if (!tmpkt.isInvalid()) {
          sts.setSimElapsedTime(tmpkt.getGenerationTime());
        }
      }
    }
  }
  */

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

  /**
   * This implements the receiving side of RFC 1055. For more information on the standard, go to
   * https://datatracker.ietf.org/doc/html/rfc1055 This method is based on the snippet from RFC
   * 1055, Page 5.
   *
   * <p>WARNING: Do not use this code yet. It needs plenty of refactoring.
   */
  protected byte[] getPayload(byte[] pktData) throws IOException {
    DataInputStream data = new DataInputStream(new ByteArrayInputStream(pktData));

    byte[] nextByte = new byte[1];

    ByteArrayOutputStream payload = new ByteArrayOutputStream();

    /* sit in a loop reading bytes until we put together
     * a whole packet.
     * Make sure not to copy them into the packet if we
     * run out of room.
     */
    // TODO:Add a MAX_PACKET_SIZE configuration arg.
    while (true) {
      /* get a character to process
       */
      data.readFully(nextByte, 0, 1);

      /* handle bytestuffing if necessary
       */

      switch (nextByte[0]) {

          /* if it's an END character then we're done with
           * the packet
           */
        case END:
          /* a minor optimization: if there is no
           * data in the packet, ignore it. This is
           * meant to avoid bothering IP with all
           * the empty packets generated by the
           * duplicate END characters which are in
           * turn sent to try to detect line noise.
           *   if(received)
           *     return received;
           *    else
           *     break;
           */
          return payload.toByteArray();

          /* if it's the same code as an ESC character, wait
           * and get another character and then figure out
           * what to store in the packet based on that.
           */
          /*Fallthrough*/
        case ESC:
          data.readFully(nextByte, 0, 1);
          /* if "c" is not one of these two, then we
           * have a protocol violation.  The best bet
           * seems to be to leave the byte alone and
           * just stuff it into the packet
           */
          switch (nextByte[0]) {
            case ESC_END:
              nextByte[0] = END;
              break;
            case ESC_ESC:
              nextByte[0] = ESC;
              break;
          }

          /* here we fall into the default handler and let
           * it store the byte for us
           */
        default:
          payload.write(nextByte[0]);
      }
    }
  }

  /* State transitions.  This just resets certain variables and possibly sends events.
   * This does not enforce legal transitions. */
  private void TransitionToState(ParserState newParserState) {
    switch (newParserState) {
      case OUT_OF_SYNC:
        outOfSyncByteCount = 0;

        eventProducer.sendWarning(
            "Lost sync after " + rcvdCaduCount + " CADUs and " + inSyncByteCount + " bytes.");
        break;

      case AT_CADU_START:
        /* Only reset inSyncByteCount and send an event if we are transitioning from the OUT_OF_SYNC state.  */
        if (ParserState.OUT_OF_SYNC == parserState) {
          inSyncByteCount = 0;

          eventProducer.sendInfo("Acquired sync after " + outOfSyncByteCount + " bytes.");
        }
        break;

      case PARSING_CADU:
        /* Do nothing. */
        break;

      case PARSING_ASM:
        /* Do nothing. */
        break;

      case CADU_COMPLETE:
        rcvdCaduCount++;
        break;
    }

    parserState = newParserState;
  }

  private static byte[] fromHexString(final String encoded) throws IllegalArgumentException {
    if ((encoded.length() % 2) != 0)
      throw new IllegalArgumentException("Input string must contain an even number of characters");

    final byte result[] = new byte[encoded.length() / 2];

    final char enc[] = encoded.toCharArray();

    for (int i = 0; i < enc.length; i += 2) {
      StringBuilder curr = new StringBuilder(2);
      curr.append(enc[i]).append(enc[i + 1]);
      result[i / 2] = (byte) Integer.parseInt(curr.toString(), 16);
    }

    return result;
  }

  /**
   * Getter methods for understanding the state of the parser. Very useful when exposed to the
   * server as system parameters.
   *
   * @return
   */
  public int getOutOfSyncByteCount() {
    return outOfSyncByteCount;
  }

  public int getInSyncByteCount() {
    return inSyncByteCount;
  }

  public int getAsmCursor() {
    return asmCursor;
  }

  public ParserState getParserState() {
    return parserState;
  }

  public int getFatFrameBytes() {
    return fatFrameBytes;
  }

  public int getCaduLength() {
    return caduLength;
  }

  public int getFixedLength() {
    return fixedLength;
  }

  public int getFatFrameCount() {
    return fatFrameCount;
  }

  public String getPacket() {
    return StringConverter.arrayToHexString(packet);
  }

  public int getRcvdCaduCount() {
    return rcvdCaduCount;
  }

  /**
   * @param fixedLength
   * @throws Exception if minLength and maxLength are not equal.
   * @throws IllegalArgumentException if fixedLength is less than 0.
   */
  public void setFixedLength(int fixedLength) throws Exception {

    if (this.minLength == this.maxLength) {
      if (fixedLength > 0) {
        this.fixedLength = fixedLength;
        this.maxLength = fixedLength;
        this.minLength = fixedLength;
      } else {
        throw new IllegalArgumentException("fixedLength must be greater than 0.");
      }
    } else {
      throw new Exception("minLength and maxLength must be equal in order to set fixedLength.");
    }
  }

  public int getMinLength() {
    return minLength;
  }

  public void setMinLength(int minLength) {
    this.minLength = minLength;
  }

  public int getMaxLength() {
    return maxLength;
  }

  public void setMaxLength(int maxLength) {
    this.maxLength = maxLength;
  }

  /** Resets rcvdCaduCount and fatFrameCount to 0. */
  public void resetCounts() {
    rcvdCaduCount = 0;
    fatFrameCount = 0;
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
      try {
        packet = getPayload(tuple.getColumn(DATA_CNAME));

        // long recTime = tuple.getColumn(PreparedCommand.CNAME_GENTIME);
        if (packet == null) {
          throw new ConfigurationException("no column named '%s' in the tuple", DATA_CNAME);
        } else {
          int trimmedPacketLength = packet.length - this.offset - this.rightTrim;
          
          if(packet.length < trimmedPacketLength) {
            log.error("Ignoring partial packet");
          } else {
            if(trimmedPacketLength < 0) {
              log.error("Trimmed packet length is < 0");
            } else {
              byte[] trimmedPacket = new byte[trimmedPacketLength];

              System.arraycopy(packet, this.offset, trimmedPacket, 0, trimmedPacketLength);
              outStream.emitTuple(
                  new Tuple(gftdef, Arrays.asList(tuple.getColumn(RECTIME_CNAME), trimmedPacket)));

              updateOutStats(trimmedPacket.length);
            }
          }
        }
      } catch (IOException e) {
        // TODO Auto-generated catch block
        e.printStackTrace();
      }
    }
  }
}
