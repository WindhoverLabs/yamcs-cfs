package com.windhoverlabs.yamcs.tctm;

import com.google.common.util.concurrent.RateLimiter;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.yamcs.ConfigurationException;
import org.yamcs.YConfiguration;
import org.yamcs.tctm.ccsds.AbstractTcFrameLink;
import org.yamcs.tctm.ccsds.TcTransferFrame;
import org.yamcs.utils.DataRateMeter;
import org.yamcs.utils.StringConverter;
import org.yamcs.yarch.ColumnDefinition;
import org.yamcs.yarch.DataType;
import org.yamcs.yarch.Stream;
import org.yamcs.yarch.Tuple;
import org.yamcs.yarch.TupleDefinition;
import org.yamcs.yarch.YarchDatabase;
import org.yamcs.yarch.YarchDatabaseInstance;

/**
 * Sends TC as TC frames (CCSDS 232.0-B-3) or TC frames embedded in CLTU (CCSDS 231.0-B-3) via a
 * YAMCS stream. One TC frame = one stream Tuple.
 *
 * <p>This class implements rate limiting. args:
 *
 * <ul>
 *   <li>frameMaxRate: maximum number of command frames to send per second.
 * </ul>
 *
 * @author Mathew Benson
 */
public class StreamTcFrameLink extends AbstractTcFrameLink implements Runnable {
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

  public void init(String yamcsInstance, String name, YConfiguration config) {
    super.init(yamcsInstance, name, config);

    String streamName = config.getString("stream");
    this.linkName = name;

    YarchDatabaseInstance ydb = YarchDatabase.getInstance(yamcsInstance);
    this.stream = getStream(ydb, streamName);

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
  public void run() {
    while (isRunningAndEnabled()) {
      if (rateLimiter != null) {
        rateLimiter.acquire();
      }
      TcTransferFrame tf = multiplexer.getFrame();
      if (tf != null) {
        byte[] data = tf.getData();
        long rectime = tf.getGenerationTime();
        if (log.isTraceEnabled()) {
          log.trace("Outgoing frame data: {}", StringConverter.arrayToHexString(data, true));
        }

        if (cltuGenerator != null) {
          data = encodeCltu(tf.getVirtualChannelId(), data);

          if (log.isTraceEnabled()) {
            log.trace("Outgoing CLTU: {}", StringConverter.arrayToHexString(data, true));
          }
        }

        /* Send it via the stream. */
        stream.emitTuple(new Tuple(gftdef, Arrays.asList(rectime, data)));

        if (tf.isBypass()) {
          ackBypassFrame(tf);
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
  }

  @Override
  protected void doEnable() throws Exception {
    thread = new Thread(this);
    thread.start();
  }

  @Override
  protected void doStart() {
    try {
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
    return Status.OK;
  }
}
