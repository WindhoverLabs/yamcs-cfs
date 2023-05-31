package com.windhoverlabs.yamcs.tctm;

import static org.yamcs.parameter.SystemParametersService.getPV;

import com.google.protobuf.util.JsonFormat;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import org.yamcs.YConfiguration;
import org.yamcs.YamcsServer;
import org.yamcs.logging.Log;
import org.yamcs.parameter.BasicParameterValue;
import org.yamcs.parameter.ParameterValue;
import org.yamcs.parameter.SystemParametersService;
import org.yamcs.protobuf.Pvalue;
import org.yamcs.protobuf.Pvalue.ParameterData;
import org.yamcs.protobuf.Yamcs.NamedObjectId;
import org.yamcs.tctm.ParameterDataLink;
import org.yamcs.tctm.ParameterSink;
import org.yamcs.time.TimeService;
import org.yamcs.xtce.SystemParameter;

/**
 * Very similar to org.yamcs.tctm.UdpParameterDataLink, except that the data is exposed to the
 * database as params. Receives PP data via UDP.
 *
 * <p>The UDP packets are protobuf encoded ParameterData. We don't use any checksum, assume it's
 * done by UDP.
 *
 * @author lgomez
 */
public class UdpParameterDataLink extends AbstractParameterDataLink
    implements ParameterDataLink, Runnable {

  private volatile int validDatagramCount = 0;
  private volatile int invalidDatagramCount = 0;
  private volatile boolean disabled = false;

  private int sequenceCount = 0;

  private TimeService timeService;
  private DatagramSocket udpSocket;
  private int port = 31002;
  private String defaultRecordingGroup;
  private Format format;

  ParameterSink parameterSink;

  private HashMap<String, ParameterValue> nameObjectIdtoParamValue;

  private Log log;
  int MAX_LENGTH = 10 * 1024;

  DatagramPacket datagram = new DatagramPacket(new byte[MAX_LENGTH], MAX_LENGTH);
  YConfiguration config;
  String name;

  private SystemParametersService collector;

  @Override
  public void init(String instance, String name, YConfiguration config) {
    super.init(instance, name, config);
    this.config = config;
    this.name = name;
    log = new Log(getClass(), instance);
    log.setContext(name);
    timeService = YamcsServer.getTimeService(instance);
    port = config.getInt("port");
    defaultRecordingGroup = config.getString("recordingGroup", "DEFAULT");
    format = config.getBoolean("json", false) ? Format.JSON : Format.PROTOBUF;
    nameObjectIdtoParamValue = new HashMap<String, ParameterValue>();
  }

  @Override
  protected void doStart() {
    if (!isDisabled()) {
      try {
        udpSocket = new DatagramSocket(port);
        new Thread(this).start();
      } catch (SocketException e) {
        notifyFailed(e);
      }
    }

    collector = SystemParametersService.getInstance(yamcsInstance);

    notifyStarted();
  }

  @Override
  protected void doStop() {
    if (udpSocket != null) {
      udpSocket.close();
    }
    notifyStopped();
  }

  public boolean isRunningAndEnabled() {
    State state = state();
    return (state == State.RUNNING || state == State.STARTING) && !disabled;
  }

  @Override
  public void run() {
    while (isRunningAndEnabled()) {
      ParameterData pdata = getNextData();
      if (pdata == null) {
        continue;
      }

      if (pdata.hasGenerationTime()) {
        log.error("Generation time must be specified for each parameter separately");
        continue;
      }

      long now = timeService.getMissionTime();
      String recgroup = pdata.hasGroup() ? pdata.getGroup() : defaultRecordingGroup;
      int sequenceNumber = pdata.hasSeqNum() ? pdata.getSeqNum() : sequenceCount++;

      // Regroup by gentime, just in case multiple parameters are submitted with different times.
      Map<Long, List<ParameterValue>> valuesByTime = new LinkedHashMap<>();

      for (Pvalue.ParameterValue gpv : pdata.getParameterList()) {
        NamedObjectId id = gpv.getId();
        if (id == null) {
          log.warn("parameter without id, skipping");
          continue;
        }
        String fqn = id.getName();
        if (id.hasNamespace()) {
          log.trace(
              "Using namespaced name for parameter {} because fully qualified name not available.",
              id);
        }

        //        System.out.println("fqn-->" + fqn);
        ParameterValue pv = BasicParameterValue.fromGpb(fqn, gpv);
        long gentime = gpv.hasGenerationTime() ? pv.getGenerationTime() : now;
        pv.setGenerationTime(gentime);

        //                pv.setParameter(SystemParameter.getForFullyQualifiedName(fqn));

        List<ParameterValue> pvals = valuesByTime.computeIfAbsent(gentime, x -> new ArrayList<>());

        //                These are all hacks, we should really be using something like
        //                org.yamcs.parameter.SystemParametersService.createSystemParameter(XtceDb,
        // String, Value, UnitType)
        if (nameObjectIdtoParamValue.get(fqn) == null) {
          // For now we are doing this ourselves since we want to be able  to add params dynamically
          // and not let the link manage it.
          //          System.out.println(
          //              "pv.getParameter().getParameterType()-->" + pv.getEngValue().getType());
          SystemParameter p =
              collector.createSystemParameter(
                  "paradigm/" + fqn, pv.getEngValue().getType(), "Data from SIM.");
          pv.setParameter(p);
          nameObjectIdtoParamValue.put(fqn, pv);
        } else {
          pv.setParameter(nameObjectIdtoParamValue.get(fqn).getParameter());
          nameObjectIdtoParamValue.put(fqn, pv);
        }

        pvals.add(pv);
      }

      for (Entry<Long, List<ParameterValue>> group : valuesByTime.entrySet()) {
        parameterSink.updateParameters(
            (long) group.getKey(), recgroup, sequenceNumber, group.getValue());
      }
    }
  }

  @Override
  public Collection<ParameterValue> getSystemParameters(long gentime) {
    super.getSystemParameters(gentime);
    List<ParameterValue> pvlist = new ArrayList<>();

    for (ParameterValue val : nameObjectIdtoParamValue.values()) {
      //		System.out.println("val.getParameter()-->" + val.getParameter());
      //      System.out.println("val.getEngValue()-->" + val.getEngValue());
      //      System.out.println("val.getParameter()-->" + val.getParameter());
      //      System.out.println("time-->" + gentime);
      pvlist.add(getPV(val.getParameter(), gentime, val.getEngValue()));
    }

    return pvlist;
  }

  /**
   * adds system parameters link status and data in/out to the list.
   *
   * <p>The inheriting classes should call super.collectSystemParameters and then add their own
   * parameters to the list
   *
   * @param time
   * @param list
   */
  protected void collectSystemParameters(long time, List<ParameterValue> list) {
    super.collectSystemParameters(time, list);
    for (ParameterValue val : nameObjectIdtoParamValue.values()) {
      //    		System.out.println("val.getParameter()-->" + val.getParameter());
      //    		System.out.println("val.getEngValue()-->" + val.getEngValue());
      //    		System.out.println("val.getParameter()-->" + val.getParameter());
      //    		System.out.println("time-->" + time);
      list.add(getPV(val.getParameter(), time, 12.5));
    }
  }

  /**
   * Called to retrieve the next packet. It blocks in reading on the UDP socket.
   *
   * @return anything that looks as a valid packet, just the size is taken into account to decide if
   *     it's valid or not
   */
  public ParameterData getNextData() {
    while (isRunning()) {
      try {
        udpSocket.receive(datagram);
        validDatagramCount++;
        return decodeDatagram(datagram.getData(), datagram.getOffset(), datagram.getLength());
      } catch (IOException e) {
        // Shutdown or disable will close the socket. That generates an exception
        // which we ignore here.
        if (!isRunning() || isDisabled()) {
          return null;
        }
        log.warn("Exception when receiving parameter data: {}'", e.toString());
        invalidDatagramCount++;
      }
    }
    return null;
  }

  /**
   * Decode {@link ParameterData} from the content of a single received UDP Datagram.
   *
   * <p>{@link UdpParameterDataLink} has configurable support for either Protobuf or JSON-encoded
   * data. Extending links may provide a custom decoder by overriding this method.
   *
   * @param data data buffer. The data received starts from {@code offset} and runs for {@code
   *     length} long.
   * @param offset offset of the data received
   * @param length length of the data received
   */
  public ParameterData decodeDatagram(byte[] data, int offset, int length) throws IOException {
    switch (format) {
      case JSON:
        try (Reader reader =
            new InputStreamReader(new ByteArrayInputStream(data, offset, length))) {
          ParameterData.Builder builder = ParameterData.newBuilder();
          JsonFormat.parser().merge(reader, builder);
          return builder.build();
        }
      case PROTOBUF:
        return ParameterData.newBuilder().mergeFrom(data, offset, length).build();
      default:
        throw new IllegalStateException("Unexpected format " + format);
    }
  }

  @Override
  public Status getLinkStatus() {
    return disabled ? Status.DISABLED : Status.OK;
  }

  /**
   * Returns statistics with the number of datagrams received and the number of invalid datagrams
   */
  @Override
  public String getDetailedStatus() {
    if (disabled) {
      return "DISABLED";
    } else {
      return String.format(
          "OK (%s)\nValid datagrams received: %d\nInvalid datagrams received: %d",
          port, validDatagramCount, invalidDatagramCount);
    }
  }

  /** Sets the disabled to true such that getNextPacket ignores the received datagrams */
  @Override
  public void disable() {
    disabled = true;
    if (udpSocket != null) {
      udpSocket.close();
      udpSocket = null;
    }
  }

  /** Sets the disabled to false such that getNextPacket does not ignore the received datagrams */
  @Override
  public void enable() {
    disabled = false;
    try {
      udpSocket = new DatagramSocket(port);
      new Thread(this).start();
    } catch (SocketException e) {
      disabled = false;
      log.warn("Failed to enable link", e);
    }
  }

  @Override
  public boolean isDisabled() {
    return disabled;
  }

  @Override
  public long getDataInCount() {
    return validDatagramCount;
  }

  @Override
  public long getDataOutCount() {
    return 0;
  }

  @Override
  public void resetCounters() {
    validDatagramCount = 0;
    invalidDatagramCount = 0;
  }

  @Override
  public void setParameterSink(ParameterSink parameterSink) {
    this.parameterSink = parameterSink;
  }

  @Override
  public YConfiguration getConfig() {
    return config;
  }

  @Override
  public String getName() {
    return name;
  }

  /** Default supported data formats */
  private static enum Format {
    JSON,
    PROTOBUF;
  }

  @Override
  protected Status connectionStatus() {
    // TODO Auto-generated method stub
    return Status.OK;
  }
}
