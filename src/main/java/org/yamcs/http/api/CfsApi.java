package org.yamcs.http.api;

// This has to be inside of org.yamcs.http.api in order to access MdbApi.verifyParameterId

import com.windhoverlabs.yamcs.cfs.api.AbstractCfsApi;
import com.windhoverlabs.yamcs.cfs.api.EntryState;
import com.windhoverlabs.yamcs.cfs.api.GetSchTableRequest;
import com.windhoverlabs.yamcs.cfs.api.ResetSdlpPacketInputStreamRequest;
import com.windhoverlabs.yamcs.cfs.api.ResetSdlpPacketInputStreamResponse;
import com.windhoverlabs.yamcs.cfs.api.SchTableEntry;
import com.windhoverlabs.yamcs.cfs.api.SchTableEntry.Builder;
import com.windhoverlabs.yamcs.cfs.api.SchTableResponse;
import com.windhoverlabs.yamcs.cfs.api.SdlpPacketInputStreamReconfigureRequest;
import com.windhoverlabs.yamcs.cfs.api.SdlpPacketInputStreamReconfigureResponse;
import com.windhoverlabs.yamcs.tctm.ccsds.SdlpPacketInputStream;
import com.windhoverlabs.yamcs.tctm.ccsds.SerialTmFrameLink;
import com.windhoverlabs.yamcs.util.CfsPlugin;
import com.windhoverlabs.yamcs.util.RegistryUtil;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.yamcs.InvalidIdentification;
import org.yamcs.NoPermissionException;
import org.yamcs.PluginManager;
import org.yamcs.Processor;
import org.yamcs.YamcsServer;
import org.yamcs.YamcsServerInstance;
import org.yamcs.api.Observer;
import org.yamcs.events.EventProducer;
import org.yamcs.events.EventProducerFactory;
import org.yamcs.http.BadRequestException;
import org.yamcs.http.Context;
import org.yamcs.http.ForbiddenException;
import org.yamcs.http.HttpException;
import org.yamcs.http.InternalServerErrorException;
import org.yamcs.mdb.XtceDbFactory;
import org.yamcs.parameter.ParameterRequestManager;
import org.yamcs.parameter.ParameterValueWithId;
import org.yamcs.parameter.ParameterWithIdConsumer;
import org.yamcs.parameter.ParameterWithIdRequestHelper;
import org.yamcs.protobuf.Pvalue.ParameterValue;
import org.yamcs.protobuf.Yamcs.NamedObjectId;
import org.yamcs.protobuf.Yamcs.Value;
import org.yamcs.security.User;
import org.yamcs.tctm.Link;
import org.yamcs.xtce.Parameter;
import org.yamcs.xtce.XtceDb;

public class CfsApi extends AbstractCfsApi<Context> {

  public static String MEMBER_SEPARATOR = ".";

  private static class CfsConsumer implements ParameterWithIdConsumer {
    LinkedBlockingQueue<List<ParameterValueWithId>> queue = new LinkedBlockingQueue<>();

    @Override
    public void update(int subscriptionId, List<ParameterValueWithId> params) {
      queue.add(params);
    }
  }

  /**
   * TODO Move this function to a util class
   *
   * @param processor
   * @param user
   * @param ids
   * @param fromCache
   * @param timeout
   * @return
   * @throws HttpException
   */
  private List<ParameterValue> doGetParameterValues(
      Processor processor, User user, List<NamedObjectId> ids, boolean fromCache, long timeout)
      throws HttpException {
    if (timeout > 60000) {
      throw new BadRequestException("Invalid timeout specified. Maximum is 60.000 milliseconds");
    }

    ParameterRequestManager prm = processor.getParameterRequestManager();
    CfsConsumer myConsumer = new CfsConsumer();
    ParameterWithIdRequestHelper pwirh = new ParameterWithIdRequestHelper(prm, myConsumer);
    List<ParameterValue> pvals = new ArrayList<>();
    try {
      if (fromCache) {
        List<ParameterValueWithId> l;
        l = pwirh.getValuesFromCache(ids, user);
        for (ParameterValueWithId pvwi : l) {
          pvals.add(pvwi.toGbpParameterValue());
        }
      } else {

        int reqId = pwirh.addRequest(ids, user);
        long t0 = System.currentTimeMillis();
        long t1;
        while (true) {
          t1 = System.currentTimeMillis();
          long remaining = timeout - (t1 - t0);
          List<ParameterValueWithId> l = myConsumer.queue.poll(remaining, TimeUnit.MILLISECONDS);
          if (l == null) {
            break;
          }

          for (ParameterValueWithId pvwi : l) {
            pvals.add(pvwi.toGbpParameterValue());
          }
          // TODO: this may not be correct: if we get a parameter multiple times, we stop here
          // before
          // receiving all parameters
          if (pvals.size() == ids.size()) {
            break;
          }
        }
        pwirh.removeRequest(reqId);
      }
    } catch (InvalidIdentification e) {
      // TODO - send the invalid parameters in a parsable form
      throw new BadRequestException("Invalid parameters: " + e.getInvalidParameters().toString());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new InternalServerErrorException("Interrupted while waiting for parameters");
    } catch (NoPermissionException e) {
      throw new ForbiddenException(e.getMessage(), e);
    }

    return pvals;
  }

  /***
   * Functions for parsing the state of sch entries in Diag message.
   *
   * @param entry
   * @return
   */
  public static EntryState getEntry1State(int entry) {
    EntryState isEntryEnabled;

    if (((entry >> 14) & 1) != 0) {
      isEntryEnabled = EntryState.ENABLED;
    } else if (((entry >> 15) & 1) != 0) {
      isEntryEnabled = EntryState.DISABLED;
    } else {
      isEntryEnabled = EntryState.UNUSED;
    }

    return isEntryEnabled;
  }

  public static EntryState getEntry2State(int entry) {
    EntryState isEntryEnabled;

    if (((entry >> 12) & 1) != 0) {
      isEntryEnabled = EntryState.ENABLED;
    } else if (((entry >> 13) & 1) != 0) {
      isEntryEnabled = EntryState.DISABLED;
    } else {
      isEntryEnabled = EntryState.UNUSED;
    }

    return isEntryEnabled;
  }

  public static EntryState getEntry3State(int entry) {
    EntryState isEntryEnabled;
    ;

    if (((entry >> 10) & 1) != 0) {
      isEntryEnabled = EntryState.ENABLED;
    } else if (((entry >> 11) & 1) != 0) {
      isEntryEnabled = EntryState.DISABLED;
    } else {
      isEntryEnabled = EntryState.UNUSED;
    }

    return isEntryEnabled;
  }

  public static EntryState getEntry4State(int entry) {
    EntryState isEntryEnabled;

    if (((entry >> 8) & 1) != 0) {
      isEntryEnabled = EntryState.ENABLED;
    } else if (((entry >> 9) & 1) != 0) {
      isEntryEnabled = EntryState.DISABLED;
    } else {
      isEntryEnabled = EntryState.UNUSED;
    }

    return isEntryEnabled;
  }

  public static EntryState getEntry5State(int entry) {
    EntryState isEntryEnabled;

    if (((entry >> 6) & 1) != 0) {
      isEntryEnabled = EntryState.ENABLED;
    } else if (((entry >> 7) & 1) != 0) {
      isEntryEnabled = EntryState.DISABLED;
    } else {
      isEntryEnabled = EntryState.UNUSED;
    }

    return isEntryEnabled;
  }

  public static EntryState getEntry6State(int entry) {
    EntryState isEntryEnabled;

    if (((entry >> 4) & 1) != 0) {
      isEntryEnabled = EntryState.ENABLED;
    } else if (((entry >> 5) & 1) != 0) {
      isEntryEnabled = EntryState.DISABLED;
    } else {
      isEntryEnabled = EntryState.UNUSED;
    }

    return isEntryEnabled;
  }

  public static EntryState getEntry7State(int entry) {
    EntryState isEntryEnabled;

    if (((entry >> 2) & 1) != 0) {
      isEntryEnabled = EntryState.ENABLED;
    } else if (((entry >> 3) & 1) != 0) {
      isEntryEnabled = EntryState.DISABLED;
    } else {
      isEntryEnabled = EntryState.UNUSED;
    }

    return isEntryEnabled;
  }

  public static EntryState getEntry8State(int entry) {
    EntryState isEntryEnabled;

    if (((entry >> 0) & 1) != 0) {
      isEntryEnabled = EntryState.ENABLED;
    } else if (((entry >> 1) & 1) != 0) {
      isEntryEnabled = EntryState.DISABLED;
    } else {
      isEntryEnabled = EntryState.UNUSED;
    }

    return isEntryEnabled;
  }

  public static EntryState getEntryState(int entry, int bitOffset) {
    EntryState isEntryEnabled;

    if (((entry >> bitOffset) & 1) != 0) {
      isEntryEnabled = EntryState.ENABLED;
    } else if (((entry >> bitOffset + 1) & 1) != 0) {
      isEntryEnabled = EntryState.DISABLED;
    } else {
      isEntryEnabled = EntryState.UNUSED;
    }

    return isEntryEnabled;
  }

  private static List<EntryState> getNextEightEntries(int entry) {
    List<EntryState> outEntries = new ArrayList<EntryState>();
    int i = 14;

    while (i >= 0) {
      outEntries.add(getEntryState(entry, i));
      i = i - 2;
    }
    return outEntries;
  }

  /**
   * Parse the pvs from the sch diag message.
   *
   * @param pVals
   * @param appMessageIds A map of message ids. It is assumed this maps to only app-specific
   *     messages ids.
   * @return A list of SchTableEntry objects that is ready to be sent to clients.
   */
  private List<SchTableEntry> parseSchDiagPvs(
      List<Value> pVals,
      LinkedHashMap<Object, Object> appMessageIds,
      int schEntriesPerSlot,
      int schTotalSlots) {
    int numberOfEntries = schEntriesPerSlot * schTotalSlots;

    int entryIndex = 0;

    List<SchTableEntry> outEntries = new ArrayList<SchTableEntry>();

    int StateIndex = 0;

    List<EntryState> allEntries = new ArrayList<EntryState>();

    // Collect all State Entries
    while (entryIndex < numberOfEntries) {

      allEntries.addAll(getNextEightEntries((pVals.get(StateIndex).getUint32Value())));

      entryIndex = entryIndex + 8;
      StateIndex++;
    }

    int minorFrame = entryIndex / schEntriesPerSlot;
    int activityNumber = 0;

    // Collect only the entries we are interested in
    for (int i = 0; i < numberOfEntries; i++) {
      minorFrame = i / schEntriesPerSlot;
      activityNumber = i - (minorFrame * schEntriesPerSlot);

      int entryMessageId = getMessageIdForEntry(i, pVals, numberOfEntries);
      // Check this messageId belongs to the app of interest

      if (appMessageIds.containsKey(entryMessageId)) {
        Builder newEntry = SchTableEntry.newBuilder();
        newEntry.setMinor(minorFrame);
        newEntry.setActivityNumber(activityNumber);
        newEntry.setState(allEntries.get(i));
        newEntry.setMessageMacro((String) appMessageIds.get(entryMessageId));
        outEntries.add(newEntry.build());
      }
    }

    return outEntries;
  }

  /**
   * fetch the messageId for entry i of sch table inside of Diag Message.
   *
   * @param i The index of the message id inside of the DIag message.
   * @param pVals
   * @return
   */
  private int getMessageIdForEntry(int i, List<Value> pVals, int numberOfEntries) {
    int outMessageId = 0;

    int entryStateOffset = (numberOfEntries / 8) + (1);

    outMessageId = pVals.get(entryStateOffset + i).getUint32Value();
    return outMessageId;
  }

  @Override
  public void getSchTable(
      Context ctx, GetSchTableRequest request, Observer<SchTableResponse> observer) {
    YamcsServerInstance instance = YamcsServer.getServer().getInstance(request.getInstance());
    List<SchTableEntry> schTableEntries = null;
    LinkedHashMap<Object, Object> allMessages = null;
    CfsPlugin plugin = null;
    if (instance == null) {
      // TODO: Publish event
      observer.completeExceptionally(
          new Exception(String.format("Instance %s does not exist", request.getInstance())));
    } else {
      EventProducer eventProducer =
          EventProducerFactory.getEventProducer(
              request.getInstance(), this.getClass().getSimpleName(), 10000);

      PluginManager pluginManager = YamcsServer.getServer().getPluginManager();
      plugin = pluginManager.getPlugin(CfsPlugin.class);
      try {
        allMessages =
            RegistryUtil.getMsgIdMapToMacro(
                RegistryUtil.getAllMessagesForApp(plugin.getRegistry(), request.getApp()));
      } catch (Exception e1) {
        eventProducer.sendWarning(
            String.format("Failed loading messages from %s.", plugin.getRegistry()));
      }

      if (allMessages != null && !allMessages.isEmpty()) {
        Processor processor = instance.getProcessor(request.getProcessor());

        if (processor != null) {
          XtceDb mdb = XtceDbFactory.getInstance(processor.getInstance());

          Parameter aggregateParam = mdb.getParameter(request.getParamPath());

          if (aggregateParam == null) {
            eventProducer.sendWarning(
                String.format("%s is not a valid parameter path", request.getParamPath()));
          } else {

            ArrayList<NamedObjectId> ids = new ArrayList<NamedObjectId>();

            NamedObjectId id = null;
            try {
              id = MdbApi.verifyParameterId(ctx, mdb, aggregateParam.getQualifiedName());
            } catch (Exception e) {
              eventProducer.sendWarning(
                  String.format(
                      "The NamedObjectId of %d could not be verified",
                      aggregateParam.getQualifiedName()));
            }
            ids.add(id);

            // TODO: Add to request message
            long timeout = 10000;
            boolean fromCache = true;

            List<Value> schVals = new ArrayList<Value>();

            List<ParameterValue> schPVals =
                doGetParameterValues(processor, ctx.user, ids, fromCache, timeout);

            if (!schPVals.isEmpty()) {
              schVals.addAll(schPVals.get(0).getRawValue().getAggregateValue().getValueList());
            }

            if (schVals.isEmpty()) {

              eventProducer.sendWarning("Sch Diag message unavailable");
            } else {
              LinkedHashMap<Object, Object> schConfig =
                  RegistryUtil.getAllConfigForApp(plugin.getRegistry(), "sch");

              if (schConfig != null) {
                // TODO Add more error checking
                LinkedHashMap<Object, Object> totalSlotsConfig =
                    (LinkedHashMap<Object, Object>) schConfig.get("SCH_TOTAL_SLOTS");

                LinkedHashMap<Object, Object> entriesPerSlotsConfig =
                    (LinkedHashMap<Object, Object>) schConfig.get("SCH_ENTRIES_PER_SLOT");

                int schEntriesPerSlot = (int) entriesPerSlotsConfig.get("value");
                int schTotalSlots = (int) totalSlotsConfig.get("value");

                schTableEntries =
                    parseSchDiagPvs(schVals, allMessages, schEntriesPerSlot, schTotalSlots);
              } else {
                eventProducer.sendWarning("Sch configuration unavailable");
              }
            }
          }

        } else {
          eventProducer.sendWarning(
              String.format("Processor %s does not exist", request.getProcessor()));
        }

      } else {
        eventProducer.sendWarning(
            String.format("No message enrties found for app:%s", request.getApp()));
      }

      if (schTableEntries != null) {
        eventProducer.sendInfo("Sch table entries parsed successfully.");
        observer.complete(SchTableResponse.newBuilder().addAllSchEntries(schTableEntries).build());
      } else {
        eventProducer.sendWarning("Unable to parse Sch table entries");
        observer.complete(SchTableResponse.newBuilder().build());
      }
    }
  }
}
