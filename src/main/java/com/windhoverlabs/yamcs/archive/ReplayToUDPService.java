package com.windhoverlabs.yamcs.archive;

import com.google.protobuf.Timestamp;
import com.google.protobuf.util.Timestamps;
import java.text.ParseException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import org.yamcs.AbstractYamcsService;
import org.yamcs.ConfigurationException;
import org.yamcs.InitException;
import org.yamcs.Processor;
import org.yamcs.ProcessorException;
import org.yamcs.ProcessorFactory;
import org.yamcs.ValidationException;
import org.yamcs.YConfiguration;
import org.yamcs.archive.ReplayOptions;
import org.yamcs.client.ParameterSubscription;
import org.yamcs.logging.Log;
import org.yamcs.protobuf.Yamcs.EndAction;
import org.yamcs.protobuf.Yamcs.ReplayRequest;

public class ReplayToUDPService extends AbstractYamcsService {
  private ReplayOptions replayOptions;
  private String processorName;
  private Processor processor;

  private String start;
  private boolean reverse; // TODO: Add to conifg
  private String stop;
  private Timestamp startTimeStamp;
  private Timestamp stopTimeStamp;

  private ParameterSubscription subscription;

  private ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
  private AtomicBoolean subscriptionDirty = new AtomicBoolean(false);

  @Override
  public void init(String yamcsInstance, String serviceName, YConfiguration config)
      throws InitException {
    this.yamcsInstance = yamcsInstance;
    this.serviceName = serviceName;
    this.config = config;
    processorName = this.config.getString("", "ReplayToUDPService");

    start = this.config.getString("start");
    stop = this.config.getString("stop");
    log = new Log(getClass(), yamcsInstance);
    try {
      startTimeStamp = Timestamps.parse(start);
      stopTimeStamp = Timestamps.parse(stop);
    } catch (ParseException e) {
      // TODO Auto-generated catch block
      e.printStackTrace();
    }

    //    TODO:Add these options to YAML
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
    } catch (ProcessorException | ConfigurationException | ValidationException | InitException e) {
      // TODO Auto-generated catch block
      e.printStackTrace();
    }
  }

  @Override
  protected void doStart() {
    // TODO Auto-generated method stub

    log.info("Starting new processor '{}'", processor.getName());
    processor.startAsync();
    processor.awaitRunning();
    processor.getTmPacketProvider();

    // Periodically check if the subscription needs a refresh
    // (PVs send individual events, so this bundles them)
    //    executor.scheduleWithFixedDelay(
    //        () -> {
    //          if (subscriptionDirty.getAndSet(false) && subscription != null) {
    //            Set<NamedObjectId> ids = getRequestedIdentifiers();
    //            // TODO:Make log level configurable
    //            //            log.info(String.format("Modifying subscription to %s", ids));
    //            subscription.sendMessage(
    //                SubscribeParametersRequest.newBuilder()
    //                    .setAction(Action.REPLACE)
    //                    .setSendFromCache(true)
    //                    .setAbortOnInvalid(false)
    //                    .setUpdateOnExpiration(true)
    //                    .addAllId(ids)
    //                    .setProcessor(currentProcessor)
    //                    .build());
    //          }
    //        },
    //        500,
    //        500,
    //        TimeUnit.MILLISECONDS);
    //  }
    notifyStarted();
  }

  @Override
  protected void doStop() {
    // TODO Auto-generated method stub
    notifyStopped();
  }
}
