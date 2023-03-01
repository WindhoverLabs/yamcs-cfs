package com.windhoverlabs.yamcs.tctm;

import com.google.common.util.concurrent.RateLimiter;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.yamcs.ConfigurationException;
import org.yamcs.YConfiguration;
import org.yamcs.tctm.ccsds.AbstractTmFrameLink;
import org.yamcs.utils.DataRateMeter;
import org.yamcs.yarch.ColumnDefinition;
import org.yamcs.yarch.DataType;
import org.yamcs.yarch.Stream;
import org.yamcs.yarch.StreamSubscriber;
import org.yamcs.yarch.Tuple;
import org.yamcs.yarch.TupleDefinition;
import org.yamcs.yarch.YarchDatabase;
import org.yamcs.yarch.YarchDatabaseInstance;

/**
 * Receives telemetry frames via a YAMCS stream. One stream Tuple = one TM frame.
 *
 * @author Mathew Benson
 */
public class StreamTmFrameLink extends AbstractTmFrameLink implements StreamSubscriber {
  private volatile int invalidDatagramCount = 0;

  String packetPreprocessorClassName;
  Object packetPreprocessorArgs;
  Thread thread;
  RateLimiter rateLimiter;
  protected Stream stream;
  protected AtomicLong packetCount = new AtomicLong(0);
  DataRateMeter packetRateMeter = new DataRateMeter();
  DataRateMeter dataRateMeter = new DataRateMeter();
  protected String linkName;
  protected AtomicBoolean disabled = new AtomicBoolean(false);

  static TupleDefinition gftdef;

  static final String RECTIME_CNAME = "rectime";
  static final String DATA_CNAME = "data";

  static {
    gftdef = new TupleDefinition();
    gftdef.addColumn(new ColumnDefinition(RECTIME_CNAME, DataType.TIMESTAMP));
    gftdef.addColumn(new ColumnDefinition(DATA_CNAME, DataType.BINARY));
  }

  /**
   * Creates a new Stream TM Frame Data Link
   *
   * @throws ConfigurationException if port is not defined in the configuration
   */
  public void init(String instance, String name, YConfiguration config)
      throws ConfigurationException {
    super.init(instance, name, config);

    String streamName = config.getString("in_stream");
    this.linkName = name;

    YarchDatabaseInstance ydb = YarchDatabase.getInstance(instance);
    this.stream = getStream(ydb, streamName);

    this.stream.addSubscriber(this);

    if (config.containsKey("frameMaxRate")) {
      rateLimiter = RateLimiter.create(config.getDouble("frameMaxRate"), 1, TimeUnit.SECONDS);
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
    packetCount.set(0);
  }

  @Override
  public void doStart() {
    notifyStarted();
  }

  @Override
  public void doStop() {
    notifyStopped();
  }

  /**
   * called when a new packet is received to update the statistics
   *
   * @param packetSize
   */
  protected void updateStats(int packetSize) {
    packetCount.getAndIncrement();
    packetRateMeter.mark(1);
    dataRateMeter.mark(packetSize);
  }

  /** returns statistics with the number of datagram received and the number of invalid datagrams */
  @Override
  public String getDetailedStatus() {
    if (isDisabled()) {
      return "DISABLED";
    } else {
      return String.format(
          "OK %nValid datagrams received: %d%nInvalid datagrams received: %d",
          frameCount.get(), invalidDatagramCount);
    }
  }

  @Override
  protected void doDisable() {}

  @Override
  protected void doEnable() {}

  @Override
  public long getDataInCount() {
    return 0;
  }

  @Override
  public long getDataOutCount() {
    return packetCount.get();
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
  public void onTuple(Stream arg0, Tuple tuple) {
    if (isRunningAndEnabled()) {

      byte[] packet;
      packet = tuple.getColumn(DATA_CNAME);

      handleFrame(timeService.getHresMissionTime(), packet, 0, packet.length);
    }
  }
}
