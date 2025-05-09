package com.windhoverlabs.com.video;

import static org.bytedeco.ffmpeg.global.avcodec.*;
import static org.bytedeco.ffmpeg.global.avformat.*;
import static org.bytedeco.ffmpeg.global.avutil.*;
import static org.bytedeco.ffmpeg.global.swscale.*;

import java.io.*;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Supplier;
import org.bytedeco.ffmpeg.avcodec.*;
import org.bytedeco.ffmpeg.avformat.*;
import org.bytedeco.ffmpeg.avutil.*;
import org.bytedeco.ffmpeg.global.avdevice;
import org.bytedeco.ffmpeg.swscale.*;
import org.bytedeco.javacpp.*;
import org.yamcs.ConfigurationException;
import org.yamcs.StandardTupleDefinitions;
import org.yamcs.TmPacket;
import org.yamcs.YConfiguration;
import org.yamcs.YamcsServer;
import org.yamcs.mdb.Mdb;
import org.yamcs.parameter.ParameterValue;
import org.yamcs.protobuf.Event.EventSeverity;
import org.yamcs.protobuf.Yamcs.Value.Type;
import org.yamcs.tctm.AbstractTmDataLink;
import org.yamcs.utils.ValueUtility;
import org.yamcs.xtce.BooleanParameterType;
import org.yamcs.xtce.FloatParameterType;
import org.yamcs.xtce.IntegerParameterType;
import org.yamcs.xtce.NameDescription;
import org.yamcs.xtce.Parameter;
import org.yamcs.xtce.ParameterType;
import org.yamcs.xtce.StringParameterType;
import org.yamcs.xtce.XtceDb;
import org.yamcs.yarch.DataType;
import org.yamcs.yarch.Stream;
import org.yamcs.yarch.Tuple;
import org.yamcs.yarch.TupleDefinition;
import org.yamcs.yarch.YarchDatabase;
import org.yamcs.yarch.YarchDatabaseInstance;
import org.yamcs.yarch.protobuf.Db.Event;

/**
 * Receives telemetry packets via UDP. One UDP datagram = one TM packet.
 *
 * <p>Options:
 *
 * <ul>
 *   <li>{@code port} - the UDP port to listen to
 *   <li>{@code maxLength} - the maximum length of the datagram (and thus the TM packet length +
 *       initialBytesToStrip). If a datagram longer than this size will be received, it will be
 *       truncated. Default: 1500 (bytes)
 *   <li>{@code initialBytesToStrip} - if configured, skip that number of bytes from the beginning
 *       of the datagram. Default: 0
 * </ul>
 */
public class VideoDataLink extends AbstractTmDataLink implements Runnable {
  private volatile int invalidDatagramCount = 0;

  private DatagramSocket tmSocket;
  private int port;

  static final int MAX_LENGTH = 1500;
  DatagramPacket datagram;
  int maxLength;
  int initialBytesToStrip;
  int rcvBufferSize;

  Mdb mdb;

  private VariableParam frameParam;

  private Stream videoStream;

  private static TupleDefinition gftdef = StandardTupleDefinitions.PARAMETER.copy();

  ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);

  /**
   * Creates a new UDP TM Data Link
   *
   * @throws ConfigurationException if port is not defined in the configuration
   */
  @Override
  public void init(String instance, String name, YConfiguration config)
      throws ConfigurationException {
    super.init(instance, name, config);
    port = config.getInt("port");
    maxLength = config.getInt("maxLength", MAX_LENGTH);
    initialBytesToStrip = config.getInt("initialBytesToStrip", 0);
    rcvBufferSize = config.getInt("rcvBufferSize", 0);
    datagram = new DatagramPacket(new byte[maxLength], maxLength);

    this.mdb = YamcsServer.getServer().getInstance(yamcsInstance).getMdb();

    frameParam = VariableParam.getForFullyQualifiedName("/yamcs/pop-os/links/Video/frameData");

    ParameterType ptype = getBasicType(mdb, Type.BINARY);

    frameParam.setParameterType(ptype);

    if (mdb.getParameter(frameParam.getQualifiedName()) == null) {
      log.debug("Adding OPCUA object as parameter to mdb:{}", frameParam.getQualifiedName());
      try {
        mdb.addParameter(frameParam, true, true);
      } catch (Exception e) {
        // TODO Auto-generated catch block
        //        internalLogger.info(e.toString());
        //        internalLogger.info("Failed to add PV:" + p.getQualifiedName());
        org.yamcs.yarch.protobuf.Db.Event ev =
            Event.newBuilder()
                .setGenerationTime(YamcsServer.getTimeService(yamcsInstance).getMissionTime())
                .setGenerationTime(YamcsServer.getTimeService(yamcsInstance).getMissionTime())
                .setSource(this.linkName)
                .setType(this.linkName)
                .setMessage("Failed to add PV:" + frameParam.getQualifiedName())
                .setSeverity(EventSeverity.ERROR)
                .build();
        eventProducer.sendEvent(ev);
      }
    } else {
      frameParam = (VariableParam) mdb.getParameter(frameParam.getQualifiedName());
    }

    YarchDatabaseInstance ydb = YarchDatabase.getInstance(yamcsInstance);

    this.videoStream = getStream(ydb, "video_frames_stream");

    List<Object> pipelines = config.getList("Pipelines");

    Initialize();

    System.out.println("pipelines-->" + pipelines);
  }

  @Override
  public void doStart() {
    if (!isDisabled()) {
      //      try {
      //        //        readVideo();
      //        streamVideoOverRTP();
      //      } catch (IOException e) {
      //        // TODO Auto-generated catch block
      //        e.printStackTrace();
      //      }
      try {
        tmSocket = new DatagramSocket(port);
        if (rcvBufferSize > 0) {
          tmSocket.setReceiveBufferSize(rcvBufferSize);
        }
        Thread thread = new Thread(this);
        thread.setName("UdpTmDataLink-" + linkName);
        thread.start();
      } catch (SocketException e) {
        notifyFailed(e);
      }
    }
    notifyStarted();
  }

  @Override
  public void doStop() {
    if (tmSocket != null) {
      tmSocket.close();
    }
    notifyStopped();
  }

  EReturnCode Initialize() {
    avformat_network_init();
    avdevice.avdevice_register_all();

    //  	av_log_set_callback(MMC_CustomLogCallback);

    //  	av_log_set_level(ConfigTable->AVLogLevel);

    return EReturnCode.OK;
  }

  private static ParameterType getOrCreateType(
      XtceDb mdb, String name, Supplier<ParameterType.Builder<?>> supplier) {

    String fqn = XtceDb.YAMCS_SPACESYSTEM_NAME + NameDescription.PATH_SEPARATOR + name;
    ParameterType ptype = mdb.getParameterType(fqn);
    if (ptype != null) {
      return ptype;
    }
    ParameterType.Builder<?> typeb = supplier.get().setName(name);

    ptype = typeb.build();
    ((NameDescription) ptype).setQualifiedName(fqn);

    return ((Mdb) mdb).addSystemParameterType(ptype);
  }

  public static ParameterType getBasicType(XtceDb mdb, Type type) {
    ParameterType pType = null;
    switch (type) {
      case BOOLEAN:
        return getOrCreateType(mdb, "boolean", () -> new BooleanParameterType.Builder());
      case STRING:
        return getOrCreateType(mdb, "string", () -> new StringParameterType.Builder());

      case FLOAT:
        return getOrCreateType(
            mdb, "float32", () -> new FloatParameterType.Builder().setSizeInBits(32));
      case DOUBLE:
        return getOrCreateType(
            mdb, "float64", () -> new FloatParameterType.Builder().setSizeInBits(64));
      case SINT32:
        return getOrCreateType(
            mdb,
            "sint32",
            () -> new IntegerParameterType.Builder().setSizeInBits(32).setSigned(true));
      case SINT64:
        return getOrCreateType(
            mdb,
            "sint64",
            () -> new IntegerParameterType.Builder().setSizeInBits(64).setSigned(true));
      case UINT32:
        return getOrCreateType(
            mdb,
            "uint32",
            () -> new IntegerParameterType.Builder().setSizeInBits(32).setSigned(false));
      case UINT64:
        return getOrCreateType(
            mdb,
            "uint64",
            () -> new IntegerParameterType.Builder().setSizeInBits(64).setSigned(false));
      default:
        break;
    }

    return pType;
  }

  private void writeFrameToDB(byte data[]) {
    TupleDefinition tdef = gftdef.copy();

    int numberfRecords = 1;

    List<Object> cols = new ArrayList<>(4 + numberfRecords);

    tdef = gftdef.copy();
    long gentime = timeService.getMissionTime();
    cols.add(gentime);
    cols.add("/yamcs/pop-os/");
    cols.add(0);
    cols.add(gentime);

    tdef.addColumn(frameParam.getQualifiedName(), DataType.PARAMETER_VALUE);

    cols.add(getPV(frameParam, gentime, data));

    pushTuple(tdef, cols);
  }

  private static Stream getStream(YarchDatabaseInstance ydb, String streamName) {
    Stream stream = ydb.getStream(streamName);
    if (stream == null) {
      try {
        ydb.execute("create stream " + streamName + gftdef.getStringDefinition());
      } catch (Exception e) {
        throw new ConfigurationException(e);
      }

      stream = ydb.getStream(streamName);
    }
    return stream;
  }

  private synchronized void pushTuple(TupleDefinition tdef, List<Object> cols) {
    Tuple t;
    t = new Tuple(tdef, cols);
    videoStream.emitTuple(t);
  }

  public ParameterValue getNewPv(Parameter parameter, long time) {
    ParameterValue pv = new ParameterValue(parameter);
    pv.setAcquisitionTime(YamcsServer.getTimeService(yamcsInstance).getMissionTime());
    pv.setGenerationTime(time);
    return pv;
  }

  public ParameterValue getPV(Parameter parameter, long time, double v) {
    ParameterValue pv = getNewPv(parameter, time);
    pv.setEngValue(ValueUtility.getDoubleValue(v));
    pv.setRawValue(ValueUtility.getDoubleValue(v));
    return pv;
  }

  public ParameterValue getPV(Parameter parameter, long time, byte v[]) {
    ParameterValue pv = getNewPv(parameter, time);
    pv.setEngValue(ValueUtility.getBinaryValue(v));
    pv.setRawValue(ValueUtility.getBinaryValue(v));
    return pv;
  }

  static void save_frame(AVFrame pFrame, int width, int height, int f_idx) throws IOException {
    // Open file
    String szFilename = String.format("frame%d_.ppm", f_idx);
    OutputStream pFile = new FileOutputStream(szFilename);

    // Write header
    pFile.write(String.format("P6\n%d %d\n255\n", width, height).getBytes());

    // Write pixel data
    BytePointer data = pFrame.data(0);
    byte[] bytes = new byte[width * 3];
    int l = pFrame.linesize(0);
    for (int y = 0; y < height; y++) {
      data.position(y * l).get(bytes);
      pFile.write(bytes);
    }

    // Close file
    pFile.close();
  }

  /**
   * Concatenates the root with the subsystems and returns a qualified name
   *
   * @param root
   */
  public static String qualifiedName(String root, String... subsystems) {
    if (root.charAt(0) != NameDescription.PATH_SEPARATOR) {
      throw new IllegalArgumentException(
          "root has to start with " + NameDescription.PATH_SEPARATOR);
    }
    StringBuilder sb = new StringBuilder();
    sb.append(root);
    for (String s : subsystems) {
      if (s.charAt(0) != NameDescription.PATH_SEPARATOR) {
        sb.append(NameDescription.PATH_SEPARATOR);
      }
      sb.append(s);
    }
    return sb.toString();
  }

  @Override
  public void run() {
    while (isRunningAndEnabled()) {
      TmPacket tmpkt = getNextPacket();
      if (tmpkt != null) {
        processPacket(tmpkt);
      }
    }
  }

  /**
   * Called to retrieve the next packet. It blocks in readining on the multicast socket
   *
   * @return anything that looks as a valid packet, just the size is taken into account to decide if
   *     it's valid or not
   */
  public TmPacket getNextPacket() {
    byte[] packet = null;

    while (isRunning()) {
      try {
        tmSocket.receive(datagram);
        int pktLength = datagram.getLength() - initialBytesToStrip;

        if (pktLength <= 0) {
          log.warn(
              "received datagram of size {} <= {} (initialBytesToStrip); ignored.",
              datagram.getLength(),
              initialBytesToStrip);
          invalidDatagramCount++;
          continue;
        }

        updateStats(datagram.getLength());
        packet = new byte[pktLength];
        System.arraycopy(
            datagram.getData(), datagram.getOffset() + initialBytesToStrip, packet, 0, pktLength);
        break;
      } catch (IOException e) {
        if (!isRunning()
            || isDisabled()) { // the shutdown or disable will close the socket and that will
          // generate an exception
          // which we ignore here
          return null;
        }
        log.warn("exception thrown when reading from the UDP socket at port {}", port, e);
      }
    }

    if (packet != null) {
      TmPacket tmPacket = new TmPacket(timeService.getMissionTime(), packet);
      tmPacket.setEarthRceptionTime(timeService.getHresMissionTime());
      return packetPreprocessor.process(tmPacket);
    } else {
      return null;
    }
  }

  /** returns statistics with the number of datagram received and the number of invalid datagrams */
  @Override
  public String getDetailedStatus() {
    if (isDisabled()) {
      return "DISABLED";
    } else {
      return String.format(
          "OK (%s) %nValid datagrams received: %d%nInvalid datagrams received: %d",
          port, packetCount.get(), invalidDatagramCount);
    }
  }

  /** Sets the disabled to true such that getNextPacket ignores the received datagrams */
  @Override
  public void doDisable() {
    if (tmSocket != null) {
      tmSocket.close();
      tmSocket = null;
    }
  }

  /**
   * Sets the disabled to false such that getNextPacket does not ignore the received datagrams
   *
   * @throws SocketException
   */
  @Override
  public void doEnable() throws SocketException {
    tmSocket = new DatagramSocket(port);
    new Thread(this).start();
  }

  @Override
  protected Status connectionStatus() {
    return Status.OK;
  }
}
