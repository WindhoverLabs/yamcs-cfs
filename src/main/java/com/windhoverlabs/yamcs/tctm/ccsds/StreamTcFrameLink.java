package com.windhoverlabs.yamcs.tctm.ccsds;

import com.google.common.util.concurrent.RateLimiter;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.yamcs.ConfigurationException;
import org.yamcs.YConfiguration;
import org.yamcs.commanding.PreparedCommand;
import org.yamcs.tctm.ccsds.AbstractTcFrameLink;
import org.yamcs.tctm.ccsds.TcTransferFrame;
import org.yamcs.utils.StringConverter;
import org.yamcs.yarch.ColumnDefinition;
import org.yamcs.yarch.DataType;
import org.yamcs.yarch.Stream;
import org.yamcs.yarch.Tuple;
import org.yamcs.yarch.TupleDefinition;
import org.yamcs.yarch.YarchDatabase;
import org.yamcs.yarch.YarchDatabaseInstance;

/**
 * Send command fames via serial interface.
 *
 * @author Mathew Benson (mbenson@windhoverlabs.com)
 */
public class StreamTcFrameLink extends AbstractTcFrameLink implements Runnable {
  RateLimiter rateLimiter;
  protected String deviceName;
  protected String syncSymbol;
  protected Stream stream;

  Map<Integer, TcTransferFrame> pendingFrames = new ConcurrentHashMap<>();

  Thread thread;

  static TupleDefinition gftdef;

  static final String RECTIME_CNAME = "rectime";
  static final String DATA_CNAME = "data";

  static {
    gftdef = new TupleDefinition();
    gftdef.addColumn(new ColumnDefinition(PreparedCommand.CNAME_GENTIME, DataType.TIMESTAMP));
    gftdef.addColumn(new ColumnDefinition(DATA_CNAME, DataType.BINARY));
  }

  /**
   * Creates a new Serial Frame Data Link
   *
   * @throws ConfigurationException if port is not defined in the configuration
   */
  public void init(String instance, String name, YConfiguration config)
      throws ConfigurationException {
    super.init(instance, name, config);

    if (config.containsKey("frameMaxRate")) {
      rateLimiter = RateLimiter.create(config.getDouble("frameMaxRate"), 1, TimeUnit.SECONDS);
    }

    this.syncSymbol = config.getString("syncSymbol", "");

    String streamName = config.getString("stream");

    YarchDatabaseInstance ydb = YarchDatabase.getInstance(instance);
    this.stream = getStream(ydb, streamName);
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
  public void run() {
    while (isRunningAndEnabled()) {

      if (rateLimiter != null) {
        rateLimiter.acquire();
      }
      TcTransferFrame tf = multiplexer.getFrame();
      if (tf != null) {
        long rectime = tf.getGenerationTime();
        byte[] data = tf.getData();
        if (log.isTraceEnabled()) {
          log.trace("Outgoing frame data: {}", StringConverter.arrayToHexString(data, true));
        }

        if (cltuGenerator != null) {
          data = cltuGenerator.makeCltu(data, false);
          if (log.isTraceEnabled()) {
            log.trace("Outgoing CLTU: {}", StringConverter.arrayToHexString(data, true));
          }
        }

        if (tf.isBypass()) {
          ackBypassFrame(tf);
        }

        this.stream.emitTuple(new Tuple(gftdef, Arrays.asList(rectime, data)));

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
      if (!isDisabled()) {}

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

  @Override
  public void sendTc(PreparedCommand pc) {
    // Not used when framing commands.
  }
}
