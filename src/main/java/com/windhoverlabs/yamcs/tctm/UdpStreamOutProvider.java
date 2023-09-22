package com.windhoverlabs.yamcs.tctm;

import com.google.common.util.concurrent.RateLimiter;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.yamcs.AbstractYamcsService;
import org.yamcs.ConfigurationException;
import org.yamcs.InitException;
import org.yamcs.Spec;
import org.yamcs.YConfiguration;
import org.yamcs.events.EventProducer;
import org.yamcs.events.EventProducerFactory;
import org.yamcs.parameter.SystemParametersProducer;
import org.yamcs.tctm.Link;
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
 * Receives telemetry fames via UDP. One UDP datagram = one TM frame.
 *
 * @author nm
 */
public class UdpStreamOutProvider extends AbstractYamcsService
    implements Link, StreamSubscriber, SystemParametersProducer {
  String host;
  int port;
  DatagramSocket socket;
  InetAddress address;
  Thread thread;
  RateLimiter rateLimiter;
  protected Stream stream;
  protected AtomicLong packetCount = new AtomicLong(0);
  DataRateMeter packetRateMeter = new DataRateMeter();
  DataRateMeter dataRateMeter = new DataRateMeter();
  protected String linkName;
  protected AtomicBoolean disabled = new AtomicBoolean(false);
  private boolean receiving;
  protected EventProducer eventProducer;

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
    host = config.getString("host");
    port = config.getInt("port");
    String streamName = config.getString("stream");
    this.linkName = name;

    YarchDatabaseInstance ydb = YarchDatabase.getInstance(instance);
    this.stream = getStream(ydb, streamName);

    this.stream.addSubscriber(this);

    try {
      address = InetAddress.getByName(host);
    } catch (UnknownHostException e) {
      throw new ConfigurationException("Cannot resolve host '" + host + "'", e);
    }
    if (config.containsKey("frameMaxRate")) {
      rateLimiter = RateLimiter.create(config.getDouble("frameMaxRate"), 1, TimeUnit.SECONDS);
    }
    receiving = true;

    eventProducer =
        EventProducerFactory.getEventProducer(instance, this.getClass().getSimpleName(), 10000);
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
    if (!isDisabled()) {
      try {
        socket = new DatagramSocket();
      } catch (SocketException e) {
        notifyFailed(e);
      }
    }
    notifyStarted();
  }

  @Override
  public void doStop() {
    socket.close();
    notifyStopped();
  }

  public boolean isRunningAndEnabled() {
    State state = state();
    return (state == State.RUNNING || state == State.STARTING) && !disabled.get();
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

  @Override
  public void enable() {
    /* TODO */
  }

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
    if (isRunningAndEnabled() && receiving) {
      byte[] pktData = tuple.getColumn(DATA_CNAME);
      if (pktData == null) {
        throw new ConfigurationException("no column named '%s' in the tuple", DATA_CNAME);
      } else {
        DatagramPacket dtg = new DatagramPacket(pktData, pktData.length, address, port);
        try {
          //        	synchronized (socket)
          //        	{
          //                socket.send(dtg);
          //        	}

          socket.send(dtg);
          updateStats(pktData.length);
        } catch (IOException e) {
          log.warn("Error sending datagram", e);
          notifyFailed(e);
          return;
        }
      }
    }
  }

  @Override
  public Spec getSpec() {
    // TODO Auto-generated method stub
    return super.getSpec();
  }

  //  Not using the regular YAMCS "doStop" since I don't think that's meant to be called by API
  // users
  //   and I don't know if it'll have any side effects.
  public void stopReceving() {
    closeSockets();
    receiving = false;
  }

  public void startReceving() {
    if (receiving) {
      eventProducer.sendInfo("Link is already receving.");
      return;
    }
    try {
      socket = new DatagramSocket(port);
    } catch (SocketException e) {
      // TODO Auto-generated catch block
      e.printStackTrace();
    }
    receiving = true;
  }

  private void closeSockets() {
    //  	synchronized (socket)
    //  	{
    //  	  socket.close();
    //
    //  	}
    socket.close();
  }
}
