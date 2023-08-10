package com.windhoverlabs.yamcs.archive;

import com.google.protobuf.Timestamp;
import com.google.protobuf.util.Timestamps;
import com.windhoverlabs.yamcs.archive.api.SimMessage;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.text.ParseException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.stream.Collectors;
import org.yamcs.AbstractYamcsService;
import org.yamcs.ConfigurationException;
import org.yamcs.InitException;
import org.yamcs.Processor;
import org.yamcs.ProcessorException;
import org.yamcs.ProcessorFactory;
import org.yamcs.ValidationException;
import org.yamcs.YConfiguration;
import org.yamcs.archive.ReplayOptions;
import org.yamcs.client.ClientException;
import org.yamcs.client.ConnectionListener;
import org.yamcs.client.ParameterSubscription;
import org.yamcs.client.YamcsClient;
import org.yamcs.logging.Log;
import org.yamcs.protobuf.Pvalue.ParameterValue;
import org.yamcs.protobuf.SubscribeParametersRequest;
import org.yamcs.protobuf.SubscribeParametersRequest.Action;
import org.yamcs.protobuf.Yamcs.EndAction;
import org.yamcs.protobuf.Yamcs.NamedObjectId;
import org.yamcs.protobuf.Yamcs.ReplayRequest;

public class ReplayToUDPService extends AbstractYamcsService
    implements ParameterSubscription.Listener, ConnectionListener {
  private ReplayOptions replayOptions;
  private String processorName;
  private Processor processor;

  private String start;
  private boolean reverse; // TODO: Add to conifg
  private String stop;
  private Timestamp startTimeStamp;
  private Timestamp stopTimeStamp;

  private ParameterSubscription subscription;

  private HashMap<String, ParameterValue> paramsToSend = new HashMap<String, ParameterValue>();

  private YamcsClient yclient;

  private DatagramSocket outSocket;
  private Map<String, String> pvMap;
  private String yamcsHost;
  private int yamcsPort;

  private String udpHost;
  private int udpPort;
  private InetAddress udpAddress;

  private boolean realtime;

  @Override
  public void init(String yamcsInstance, String serviceName, YConfiguration config)
      throws InitException {
    this.yamcsInstance = yamcsInstance;
    this.serviceName = serviceName;
    this.config = config;
    //    TODO:Probably a much easier way of doing this...
    //    YamcsServer.getServer().getInstance(yamcsInstance).addProcessor(processor);
    //    YamcsServer.getServer().getInstance(yamcsInstance)

    realtime = this.config.getBoolean("realtime", false);
    start = this.config.getString("start");
    stop = this.config.getString("stop");

    pvMap = this.config.getMap("pvMap");

    yamcsHost = this.getConfig().getString("yamcsHost", "http://localhost");
    yamcsPort = this.getConfig().getInt("yamcsPort", 8090);

    udpHost = this.getConfig().getString("udpHost", "172.16.100.237");
    udpPort = this.getConfig().getInt("udpPort", 8000);

    log = new Log(getClass(), yamcsInstance);

    try {
      startTimeStamp = Timestamps.parse(start);
      stopTimeStamp = Timestamps.parse(stop);
    } catch (ParseException e) {
      // TODO Auto-generated catch block
      e.printStackTrace();
    }

    //    TODO:Add these options to YAML

    if (!this.realtime) {
      processorName = this.config.getString("processorName", "ReplayToUDPService");
      replayOptions =
          new ReplayOptions(
              ReplayRequest.newBuilder()
                  .setStart(startTimeStamp)
                  .setStop(stopTimeStamp)
                  .setEndAction(EndAction.LOOP)
                  .setAutostart(true)
                  .build());

      try {
        processor =
            ProcessorFactory.create(
                yamcsInstance,
                processorName,
                "Archive",
                ReplayToUDPService.class.toString(),
                replayOptions);
      } catch (ProcessorException
          | ConfigurationException
          | ValidationException
          | InitException e) {
        // TODO Auto-generated catch block
        e.printStackTrace();
      }
    } else {
      processorName = "realtime";
    }
  }

  @Override
  protected void doStart() {
    // TODO Auto-generated method stub
    if (!realtime) {
      log.info("Starting new processor '{}'", processor.getName());
      processor.startAsync();
      processor.awaitRunning();
    }

    //    TODO: This is unnecessarily complicated
    yclient =
        YamcsClient.newBuilder(yamcsHost + ":" + yamcsPort)
            //            .withConnectionAttempts(config.getInt("connectionAttempts", 20))
            //            .withRetryDelay(reconnectionDelay)
            //            .withVerifyTls(config.getBoolean("verifyTls", true))
            .build();
    yclient.addConnectionListener(this);

    try {
      yclient.connectWebSocket();
    } catch (ClientException e) {
      // TODO Auto-generated catch block
      e.printStackTrace();
    }

    try {
      outSocket = new DatagramSocket();
    } catch (SocketException e) {
      // TODO Auto-generated catch block
      e.printStackTrace();
    }

    try {
      udpAddress = InetAddress.getByName(udpHost);
    } catch (UnknownHostException e) {
      // TODO Auto-generated catch block
      e.printStackTrace();
    }
    ;

    notifyStarted();
  }

  public static NamedObjectId identityOf(String pvName) {
    return NamedObjectId.newBuilder().setName(pvName).build();
  }

  /** Async adds a Yamcs PV for receiving updates. */
  public void register(String pvName) {
    NamedObjectId id = identityOf(pvName);
    try {
      subscription.sendMessage(
          SubscribeParametersRequest.newBuilder()
              .setInstance(this.yamcsInstance)
              .setProcessor(processorName)
              .setSendFromCache(true)
              .setAbortOnInvalid(false)
              .setUpdateOnExpiration(false)
              .addId(id)
              .setAction(Action.ADD)
              .build());
    } catch (Exception e) {
      System.out.println("e:" + e);
    }
  }

  @Override
  protected void doStop() {
    // TODO Auto-generated method stub
    if (!realtime) {
      processor.doStop();
      outSocket.close();
    }
    notifyStopped();
  }

  @Override
  public void onData(List<ParameterValue> values) {
    // TODO Auto-generated method stub
    for (ParameterValue p : values) {
      if (pvMap.containsValue(p.getId().getName())) {
        String pvLabel =
            pvMap.entrySet().stream()
                .filter(entry -> entry.getValue().equals(p.getId().getName()))
                .map(Entry::getKey)
                .collect(Collectors.toList())
                .get(0);
        paramsToSend.put(pvLabel, p);
      }
    }
    SimMessage.VehicleStateMessage.Builder msgBuilder = SimMessage.VehicleStateMessage.newBuilder();
    for (Map.Entry<String, ParameterValue> pSet : paramsToSend.entrySet()) {
      org.yamcs.protobuf.Yamcs.Value pv = pSet.getValue().getEngValue();
      switch (pv.getType()) {
        case AGGREGATE:
          break;
        case ARRAY:
          break;
        case BINARY:
          break;
        case BOOLEAN:
          break;
        case DOUBLE:
          msgBuilder.setField(
              SimMessage.VehicleStateMessage.getDescriptor().findFieldByName(pSet.getKey()),
              pv.getDoubleValue());
          break;
        case ENUMERATED:
          break;
        case FLOAT:
          msgBuilder.setField(
              SimMessage.VehicleStateMessage.getDescriptor().findFieldByName(pSet.getKey()),
              pv.getFloatValue());
          break;
        case NONE:
          break;
        case SINT32:
          break;
        case SINT64:
          break;
        case STRING:
          break;
        case TIMESTAMP:
          break;
        case UINT32:
          break;
        case UINT64:
          break;
        default:
          break;
      }
    }
    SimMessage.VehicleStateMessage msg = msgBuilder.build();
    DatagramPacket dtg =
        new DatagramPacket(msg.toByteArray(), msg.toByteArray().length, udpAddress, udpPort);

    try {
      outSocket.send(dtg);
    } catch (IOException e) {
      // TODO Auto-generated catch block
      e.printStackTrace();
    }
  }

  @Override
  public void connecting() {
    // TODO Auto-generated method stub

  }

  public void connected() {
    //	  TODO:Send Event instead?
    //    System.out.println("*****connected*****");
    subscription = yclient.createParameterSubscription();
    subscription.addListener(this);
    // TODO:Make this configurable
    for (Map.Entry<String, String> pvName : pvMap.entrySet()) {
      register(pvName.getValue());
    }
  }

  @Override
  public void connectionFailed(Throwable cause) {
    // TODO Auto-generated method stub

  }

  @Override
  public void disconnected() {
    // TODO Auto-generated method stub

  }
}
