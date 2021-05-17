package org.yamcs.http.api;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.yamcs.InvalidIdentification;
import org.yamcs.NoPermissionException;

//This has to be inside of org.yamcs.http.api in order to access MdbApi.verifyParameterId

import org.yamcs.Processor;
import org.yamcs.YamcsServer;
import org.yamcs.YamcsServerInstance;
import org.yamcs.api.Observer;
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

import com.windhoverlabs.yamcs.cfs.AbstractCfsUtilApi;
import com.windhoverlabs.yamcs.cfs.GetSchTableRequest;
import com.windhoverlabs.yamcs.cfs.SchTableEntry;
import com.windhoverlabs.yamcs.cfs.SchTableResponse;
import org.yamcs.xtce.AggregateParameterType;

public class CfsUtilApi extends AbstractCfsUtilApi<Context> {

    public static String MEMBER_SEPARATOR = ".";

    private static class CfsConsumer implements ParameterWithIdConsumer {
        LinkedBlockingQueue<List<ParameterValueWithId>> queue = new LinkedBlockingQueue<>();

        @Override
        public void update(int subscriptionId, List<ParameterValueWithId> params) {
            queue.add(params);
        }
    }

    private static enum EntryState {
        ENABLED, DISABLED, UNUSED;
    }

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
    private void getAggregateQualifiedNames(Parameter parameter, String parameterQualifiedName,
            ArrayList<String> outNames) {
        AggregateDataType type = ((AggregateDataType) parameter.getParameterType());

        for (Member m : type.getMemberList()) {

            outNames.add(parameterQualifiedName + MEMBER_SEPARATOR + m.getName());
        }
    }

    private HashMap<String, Integer> parseSchDiag(Processor processor, Context ctx) {
        HashMap<String, Integer> outSchDiag = new HashMap<String, Integer>();

        System.out.println("processing sch table..");

        XtceDb mdb = XtceDbFactory.getInstance(processor.getInstance());

        // mdb.getParameter("").get

        // This looks kind of ugly, but I don't think there is another way of doing it.
        NamedObjectId id = MdbApi.verifyParameterId(ctx, mdb, "/cfs//to/TO_HkTlm_t.ChannelMemInfo_0_.PeakMemInUse");
        //
        long timeout = 10000;
        boolean fromCache = true;
        //
        List<NamedObjectId> ids = Arrays.asList(id);
        // There is no way for me to get aggregates. Will have assemble them manually...
        List<ParameterValue> pvals = doGetParameterValues(processor, ctx.user, ids, fromCache, timeout);

        return outSchDiag;
    }

    public EntryState isEntry1Enabled(int entry) {
        EntryState isEntryEnabled;

        System.out.println("((entry>>1)  " + (Integer.toString((entry << 1) & 1)));
        System.out.println("(entry>>2) :" + (Integer.toString((entry << 2) & 1)));

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

        System.out.println("((entry>>1)  " + (Integer.toString((entry << 1) & 1)));
        System.out.println("(entry>>2) :" + (Integer.toString((entry << 2) & 1)));

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

        System.out.println("((entry>>1)  " + (Integer.toString((entry << 1) & 1)));
        System.out.println("(entry>>2) :" + (Integer.toString((entry << 2) & 1)));

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

        System.out.println("((entry>>1)  " + (Integer.toString((entry << 1) & 1)));
        System.out.println("(entry>>2) :" + (Integer.toString((entry << 2) & 1)));

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

        System.out.println("((entry>>1)  " + (Integer.toString((entry << 1) & 1)));
        System.out.println("(entry>>2) :" + (Integer.toString((entry << 2) & 1)));

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

        System.out.println("((entry>>1)  " + (Integer.toString((entry << 1) & 1)));
        System.out.println("(entry>>2) :" + (Integer.toString((entry << 2) & 1)));

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

        System.out.println("((entry>>1)  " + (Integer.toString((entry << 1) & 1)));
        System.out.println("(entry>>2) :" + (Integer.toString((entry << 2) & 1)));

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

        System.out.println("((entry>>1)  " + (Integer.toString((entry << 1) & 1)));
        System.out.println("(entry>>2) :" + (Integer.toString((entry << 2) & 1)));

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

    private static EntryState isEntryEnabled(int entry, int bitOffset) {
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
            outEntries.add(isEntryEnabled(entry, i));
            i = i - 2;
        }

        System.out.println(String.format("entries %s for %d", outEntries, entry));

        return outEntries;
    }

    public List<SchTableEntry> parseSchDiagPvs(List<ParameterValue> pVals, int messageId) {
        // TODO: Fetch from YAML
        int numberOfEntries = 3750;

        int entryIndex = 0;

        List<SchTableEntry> outEntries = new ArrayList<SchTableEntry>();

        int StateIndex = 0;
        
        int frame_index = 0;

        while (entryIndex < numberOfEntries) {
            int SCH_ENTRIES_PER_SLOT = 15;
            
            int minor_frame = frame_index / SCH_ENTRIES_PER_SLOT;
            System.out.println("minor frame:" + minor_frame);

//            getNextEightEntries( );
            
            List<EntryState> nextEightEntries = getNextEightEntries((pVals.get(StateIndex).getRawValue().getUint32Value()));
            
            for(int i = 0;i<nextEightEntries.size(); i++) 
            {
                entryMessageId = getMessageIdForEntry(entryIndex+i);
                 if(entryMessageId == messageId) {
                     
                     SchTableEntry newEntry = new SchTableEntry(); entryStatus = getEntryStatus(nextEightEntries[i]);
                     newEntry.setStatus(entryStatus); newEnttry.setMesageId(messageId);
                     
                     int minor_frame = entryIndex+i / SCH_ENTRIES_PER_SLOT; int activity_number = entryIndex+i - (minor_frame
                     SCH_ENTRIES_PER_SLOT);
                     
                     newEntry.setnMinorFrame(minor_frame); newEntry.setActivityNumber(activity_number);
                     
                     }
            }
            /**
             *  
             * for (int i = 0;i<nextEightEntries.length(); i++) {
             * 
             * entryMessageId = getMessageIdForEntry(entryIndex+i)
             * 
             * if(entryMessageId == messageId) {
             * 
             * SchTableEntry newEntry = new SchTableEntry(); entryStatus = getEntryStatus(nextEightEntries[i]);
             * newEntry.setStatus(entryStatus); newEnttry.setMesageId(messageId);
             * 
             * int minor_frame = entryIndex+i / SCH_ENTRIES_PER_SLOT; int activity_number = entryIndex+i - (minor_frame
             * *SCH_ENTRIES_PER_SLOT);
             * 
             * newEntry.setnMinorFrame(minor_frame); newEntry.setActivityNumber(activity_number);
             * 
             * }
             * 
             * 
             * }
             * 
             * entryIndex += 8;
             * 
             */

            

             
//            System.out.println("minor frame:" + minor_frame);
            
//            System.out.println("entryIndex:" + entryIndex);
            
            entryIndex = entryIndex + 8;
            StateIndex++;
            
            frame_index  = frame_index + 8;
        }

        return null;
    }

    private int getMessageIdForEntry(int i) {
        // TODO Fetch from YAML
        
        return 6423;
    }

    @Override
    public void getSchTable(Context ctx, GetSchTableRequest request, Observer<SchTableResponse> observer) {

        System.out.println("processing sch table..");
        YamcsServerInstance instance = YamcsServer.getServer().getInstance(request.getInstance());

        if (instance == null) {
            // TODO: Publish event
            observer.completeExceptionally(
                    new Exception(String.format("Instance %s does not exist", request.getInstance())));
        } else {
            System.out.println("processor:" + instance.getProcessor("realtime"));
            Processor processor = instance.getProcessor("realtime");
            if (processor == null) {
                // TODO: Publish event
                observer.completeExceptionally(
                        new Exception(String.format("Processor %s does not exist", "realtime")));
            }

            System.out.println("Entry:" + isEntry1Enabled(21840));

            XtceDb mdb = XtceDbFactory.getInstance(processor.getInstance());
            //
            // // mdb.getParameter("").get
            //
            // ArrayList<String> pNames = new ArrayList<String>();
            //
            // //
            // // There is no way for me to get aggregates. Will have assemble them manually...
            //

            ArrayList<String> pNames = new ArrayList<String>();
            getAggregateQualifiedNames(mdb.getParameter("/cfs//sch/SCH_DiagPacket_t"), "/cfs//sch/SCH_DiagPacket_t",
                    pNames);
            ArrayList<NamedObjectId> ids = new ArrayList<NamedObjectId>();

            for (String pName : pNames) {
                // System.out.println("pName:" + pName);
                // This looks kind of ugly, but I don't think there is another way of doing it.
                System.out.println("verifyParameterId1");
                NamedObjectId id = MdbApi.verifyParameterId(ctx, mdb, pName);
                ids.add(id);
                System.out.println("verifyParameterId2");

            }
            long timeout = 10000;
            boolean fromCache = true;
            List<ParameterValue> schPVals = doGetParameterValues(processor, ctx.user, ids, fromCache, timeout);

            int SCH_ENTRIES_PER_SLOT = 15;
            // int SCH_TOTAL_SLOTS = 250;
            //
            // for (int i = 0; i < 3750; i++) {
            // int minor_frame = i / SCH_ENTRIES_PER_SLOT;
            // int activity_number = i - (minor_frame * SCH_ENTRIES_PER_SLOT);
            //
            // System.out.println(String.format("minor frame:%d, activity_number:%d", minor_frame, activity_number));
            // }
            //
            // System.out.println("pvals:" + schPVals.get(0).getEngValue().getUint32Value());
            //
            // parseSchDiag(processor, ctx);
            //

            parseSchDiagPvs(schPVals, 0xfff);
            observer.complete(SchTableResponse.newBuilder().build());
        }

    }

}
