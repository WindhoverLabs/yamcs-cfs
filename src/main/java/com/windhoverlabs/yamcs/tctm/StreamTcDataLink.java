package com.windhoverlabs.yamcs.tctm;

import java.io.IOException;
import java.util.Arrays;
import org.yamcs.ConfigurationException;
import org.yamcs.YConfiguration;
import org.yamcs.commanding.PreparedCommand;
import org.yamcs.tctm.AbstractThreadedTcDataLink;
import org.yamcs.yarch.ColumnDefinition;
import org.yamcs.yarch.DataType;
import org.yamcs.yarch.Stream;
import org.yamcs.yarch.Tuple;
import org.yamcs.yarch.TupleDefinition;
import org.yamcs.yarch.YarchDatabase;
import org.yamcs.yarch.YarchDatabaseInstance;

/**
 * Sends raw packets on UDP socket.
 *
 * @author nm
 */
public class StreamTcDataLink extends AbstractThreadedTcDataLink {

  protected String streamName;
  protected Stream stream;

  static TupleDefinition gftdef;

  static final String RECTIME_CNAME = "rectime";
  static final String DATA_CNAME = "data";

  static {
    gftdef = new TupleDefinition();
    gftdef.addColumn(new ColumnDefinition(RECTIME_CNAME, DataType.TIMESTAMP));
    gftdef.addColumn(new ColumnDefinition(DATA_CNAME, DataType.BINARY));
  }

  @Override
  public void init(String yamcsInstance, String name, YConfiguration config)
      throws ConfigurationException {
    super.init(yamcsInstance, name, config);
    this.streamName = config.getString("out_stream");

    YarchDatabaseInstance ydb = YarchDatabase.getInstance(yamcsInstance);
    this.stream = getStream(ydb, streamName);
  }

  @Override
  public String getDetailedStatus() {
    return String.format("OK");
  }

  @Override
  public void uplinkCommand(PreparedCommand pc) throws IOException {
    byte[] binary = postprocess(pc);
    if (binary == null) {
      return;
    }

    this.stream.emitTuple(new Tuple(this.gftdef, Arrays.asList(pc.getGenerationTime(), binary)));

    dataCount.getAndIncrement();
    ackCommand(pc.getCommandId());
  }

  @Override
  protected Status connectionStatus() {
    return Status.OK;
  }

  @Override
  public void shutDown() {}

  @Override
  protected void startUp() {}

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
}
