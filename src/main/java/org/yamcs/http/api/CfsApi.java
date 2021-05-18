package org.yamcs.http.api;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.yamcs.InvalidIdentification;
import org.yamcs.NoPermissionException;
import org.yamcs.PluginManager;

//This has to be inside of org.yamcs.http.api in order to access MdbApi.verifyParameterId

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
import org.yamcs.parameter.ParameterRequestManager;
import org.yamcs.parameter.ParameterValueWithId;
import org.yamcs.parameter.ParameterWithIdConsumer;
import org.yamcs.parameter.ParameterWithIdRequestHelper;
import org.yamcs.protobuf.Pvalue.ParameterValue;
import org.yamcs.protobuf.Yamcs.NamedObjectId;
import org.yamcs.security.User;
import org.yamcs.xtce.AggregateDataType;
import org.yamcs.xtce.Member;
import org.yamcs.xtce.Parameter;
import org.yamcs.xtce.XtceDb;
import org.yamcs.xtceproc.XtceDbFactory;
import org.yamcs.yarch.DataType;

import com.windhoverlabs.yamcs.cfs.AbstractCfsApi;
import com.windhoverlabs.yamcs.cfs.EntryState;
import com.windhoverlabs.yamcs.cfs.GetSchTableRequest;
import com.windhoverlabs.yamcs.cfs.SchTableEntry;
import com.windhoverlabs.yamcs.cfs.SchTableEntry.Builder;
import com.windhoverlabs.yamcs.cfs.SchTableResponse;
import com.windhoverlabs.yamcs.util.CfsPlugin;
import com.windhoverlabs.yamcs.util.RegistryUtil;

public class CfsApi extends AbstractCfsApi<Context> {

    public static String MEMBER_SEPARATOR = ".";

    private static class CfsConsumer implements ParameterWithIdConsumer {
        LinkedBlockingQueue<List<ParameterValueWithId>> queue = new LinkedBlockingQueue<>();

        @Override
        public void update(int subscriptionId, List<ParameterValueWithId> params) {
            queue.add(params);
        }
    }

    //TODO Move this function to a util class
    private List<ParameterValue> doGetParameterValues(Processor processor, User user, List<NamedObjectId> ids,
            boolean fromCache, long timeout) throws HttpException {
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
                    // TODO: this may not be correct: if we get a parameter multiple times, we stop here before
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

    /**
     * For now this method only goes 1-level deep into structures. Nested Structures are no supported at the moment.
     * 
     * @param parameter
     * @param parameterQualifiedName
     * @param outNames
     * @param mdb
     */
    private void getAggregateQualifiedNames(Parameter parameter,
            ArrayList<String> outNames) {
        AggregateDataType type = ((AggregateDataType) parameter.getParameterType());

        for (Member m : type.getMemberList()) {
            outNames.add(parameter.getQualifiedName() + MEMBER_SEPARATOR + m.getName());
        }
    }

    public EntryState isEntry1Enabled(int entry) {
        EntryState isEntryEnabled;

        if (((entry >> 14) & 1) != 0) {
            isEntryEnabled = EntryState.ENABLED;
        }

        else if (((entry >> 15) & 1) != 0) {
            isEntryEnabled = EntryState.DISABLED;
        }

        else {
            isEntryEnabled = EntryState.UNUSED;
        }

        return isEntryEnabled;
    }

    public EntryState isEntry2Enabled(int entry) {
        EntryState isEntryEnabled;

        if (((entry >> 12) & 1) != 0) {
            isEntryEnabled = EntryState.ENABLED;
        }

        else if (((entry >> 13) & 1) != 0) {
            isEntryEnabled = EntryState.DISABLED;
        }

        else {
            isEntryEnabled = EntryState.UNUSED;
        }

        return isEntryEnabled;
    }

    public EntryState isEntry3Enabled(int entry) {
        EntryState isEntryEnabled;
        ;

        if (((entry >> 10) & 1) != 0) {
            isEntryEnabled = EntryState.ENABLED;
        }

        else if (((entry >> 11) & 1) != 0) {
            isEntryEnabled = EntryState.DISABLED;
        }

        else {
            isEntryEnabled = EntryState.UNUSED;
        }

        return isEntryEnabled;
    }

    public EntryState isEntry4Enabled(int entry) {
        EntryState isEntryEnabled;

        if (((entry >> 8) & 1) != 0) {
            isEntryEnabled = EntryState.ENABLED;
        }

        else if (((entry >> 9) & 1) != 0) {
            isEntryEnabled = EntryState.DISABLED;
        }

        else {
            isEntryEnabled = EntryState.UNUSED;
        }

        return isEntryEnabled;
    }

    public EntryState isEntry5Enabled(int entry) {
        EntryState isEntryEnabled;

        if (((entry >> 6) & 1) != 0) {
            isEntryEnabled = EntryState.ENABLED;
        }

        else if (((entry >> 7) & 1) != 0) {
            isEntryEnabled = EntryState.DISABLED;
        }

        else {
            isEntryEnabled = EntryState.UNUSED;
        }

        return isEntryEnabled;
    }

    public EntryState isEntry6Enabled(int entry) {
        EntryState isEntryEnabled;

        if (((entry >> 4) & 1) != 0) {
            isEntryEnabled = EntryState.ENABLED;
        }

        else if (((entry >> 5) & 1) != 0) {
            isEntryEnabled = EntryState.DISABLED;
        }

        else {
            isEntryEnabled = EntryState.UNUSED;
        }

        return isEntryEnabled;
    }

    public EntryState isEntry7Enabled(int entry) {
        EntryState isEntryEnabled;

        if (((entry >> 2) & 1) != 0) {
            isEntryEnabled = EntryState.ENABLED;
        }

        else if (((entry >> 3) & 1) != 0) {
            isEntryEnabled = EntryState.DISABLED;
        }

        else {
            isEntryEnabled = EntryState.UNUSED;
        }

        return isEntryEnabled;
    }

    public EntryState isEntry8Enabled(int entry) {
        EntryState isEntryEnabled;

        if (((entry >> 0) & 1) != 0) {
            isEntryEnabled = EntryState.ENABLED;
        }

        else if (((entry >> 1) & 1) != 0) {
            isEntryEnabled = EntryState.DISABLED;
        }

        else {
            isEntryEnabled = EntryState.UNUSED;
        }

        return isEntryEnabled;
    }

    private static EntryState getEntryState(int entry, int bitOffset) {
        EntryState isEntryEnabled;

        if (((entry >> bitOffset) & 1) != 0) {
            isEntryEnabled = EntryState.ENABLED;
        }

        else if (((entry >> bitOffset + 1) & 1) != 0) {
            isEntryEnabled = EntryState.DISABLED;
        }

        else {
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
     * @param appMessageIds
     *            A map of message ids. It is assumed this maps to only app-specific messages ids.
     * @return A list of SchTableEntry objects that is ready to be sent to clients.
     */
    private List<SchTableEntry> parseSchDiagPvs(List<ParameterValue> pVals, LinkedHashMap<Object, Object> appMessageIds,
            int schEntriesPerSlot, int schTotalSlots) {
        // TODO: Fetch from YAML
        int numberOfEntries = schEntriesPerSlot * schTotalSlots;

        int entryIndex = 0;

        List<SchTableEntry> outEntries = new ArrayList<SchTableEntry>();

        int StateIndex = 0;

        List<EntryState> allEntries = new ArrayList<EntryState>();

        // Collect all State Entries
        while (entryIndex < numberOfEntries) {

            allEntries.addAll(getNextEightEntries((pVals.get(StateIndex).getRawValue().getUint32Value())));

            entryIndex = entryIndex + 8;
            StateIndex++;
        }

        int minorFrame = entryIndex / schEntriesPerSlot;
        int activityNumber = 0;

        for (int i = 0; i < numberOfEntries; i++) {
            minorFrame = i / schEntriesPerSlot;
            activityNumber = i - (minorFrame * schEntriesPerSlot);

            int entryMessageId = getMessageIdForEntry(i, pVals);
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
     * @param i
     *            The index of the message id inside of the DIag message.
     * @param pVals
     * @return
     */
    private int getMessageIdForEntry(int i, List<ParameterValue> pVals) {
        // TODO: Fetch from YAML
        int numberOfEntries = 3750;

        int outMessageId = 0;

        int entryStateOffset = (numberOfEntries / 8) + (1);

        outMessageId = pVals.get(entryStateOffset + i).getEngValue().getUint32Value();
        return outMessageId;
    }

    @Override
    public void getSchTable(Context ctx, GetSchTableRequest request, Observer<SchTableResponse> observer) {
        YamcsServerInstance instance = YamcsServer.getServer().getInstance(request.getInstance());
        List<SchTableEntry> schTableEntries = null;
        LinkedHashMap<Object, Object> allMessages = null;
        CfsPlugin plugin = null;
        if (instance == null) {
            // TODO: Publish event
            observer.completeExceptionally(
                    new Exception(String.format("Instance %s does not exist", request.getInstance())));
        } else {
            EventProducer eventProducer = EventProducerFactory.getEventProducer(request.getInstance(),
                    this.getClass().getSimpleName(), 10000);

            PluginManager pluginManager = YamcsServer.getServer().getPluginManager();
            plugin = pluginManager.getPlugin(CfsPlugin.class);
            try {
                allMessages = RegistryUtil
                        .getMsgIdMapToMacro(RegistryUtil.getAllMessagesForApp(plugin.getRegistry(), request.getApp()));
            } catch (Exception e1) {
                // TODO Auto-generated catch block
                eventProducer.sendWarning(String.format("Failed loading messages from %s.", plugin.getRegistry()));
            }

            if (allMessages != null) {
                Processor processor = instance.getProcessor(request.getProcessor());
                if (processor == null) {
                    // TODO: Publish event
                    eventProducer.sendWarning(String.format("Processor %s does not exist", request.getProcessor()));
                }

                XtceDb mdb = XtceDbFactory.getInstance(processor.getInstance());

                ArrayList<String> pNames = new ArrayList<String>();
                Parameter aggregateParam = mdb.getParameter(request.getParamPath());

                if (aggregateParam == null) {
                    eventProducer.sendWarning(String.format("%d is not a valid Parameter", request.getParamPath()));
                } else {

                    getAggregateQualifiedNames(aggregateParam, pNames);
                    ArrayList<NamedObjectId> ids = new ArrayList<NamedObjectId>();

                    for (String pName : pNames) {
                        // This looks kind of ugly, but I don't think there is another way of doing it.
                        NamedObjectId id = null;
                        try {
                            id = MdbApi.verifyParameterId(ctx, mdb, pName);
                        } catch (Exception e) {
                            eventProducer.sendWarning(String.format("%d is not a valid Parameter", pName));
                            break;
                        }
                        ids.add(id);
                    }

                    // TODO: Add to request message
                    long timeout = 10000;
                    boolean fromCache = true;

                    List<ParameterValue> schPVals = doGetParameterValues(processor, ctx.user, ids, fromCache, timeout);

                    if (schPVals.isEmpty()) {
                        eventProducer.sendWarning("Sch Diag message unavailable");
                    }

                    else {
                        LinkedHashMap<Object, Object> schConfig = RegistryUtil.getAllConfigForApp(plugin.getRegistry(), "sch");
                        
                        if(schConfig!= null) 
                        { 
                            //TODO Add more error checking
                            LinkedHashMap<Object, Object> totalSlotsConfig =  (LinkedHashMap<Object, Object>) schConfig.get("SCH_TOTAL_SLOTS");
                            
                            LinkedHashMap<Object, Object> entriesPerSlotsConfig = (LinkedHashMap<Object, Object>)  schConfig.get("SCH_ENTRIES_PER_SLOT");

                            
                            int schEntriesPerSlot = (int) entriesPerSlotsConfig.get("value");
                            int schTotalSlots = (int) totalSlotsConfig.get("value");
                            schTableEntries = parseSchDiagPvs(schPVals, allMessages, schEntriesPerSlot, schTotalSlots);
                        }
                        
                        else 
                        {
                            eventProducer.sendWarning("Sch configuration unavailable");

 
                        }
                        
                    }
                }
            }
            if (schTableEntries != null) {
                eventProducer.sendInfo("Sch table entries parsed successfully.");
                observer.complete(SchTableResponse.newBuilder().addAllSchEntry(schTableEntries).build());
            } else {
                eventProducer.sendWarning("Unable to parse Sch table entries");
                observer.complete(SchTableResponse.newBuilder().build());
            }
        }
    }

}
