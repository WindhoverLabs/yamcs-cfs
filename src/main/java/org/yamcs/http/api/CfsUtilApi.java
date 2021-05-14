package org.yamcs.http.api;

import java.util.ArrayList;
import java.util.Arrays;
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
import org.yamcs.xtce.XtceDb;
import org.yamcs.xtceproc.XtceDbFactory;

import com.windhoverlabs.yamcs.cfs.AbstractCfsUtilApi;
import com.windhoverlabs.yamcs.cfs.GetSchTableRequest;
import com.windhoverlabs.yamcs.cfs.SchTableResponse;

public class CfsUtilApi extends AbstractCfsUtilApi<Context> {

    
    private static class CfsConsumer implements ParameterWithIdConsumer {
        LinkedBlockingQueue<List<ParameterValueWithId>> queue = new LinkedBlockingQueue<>();

        @Override
        public void update(int subscriptionId, List<ParameterValueWithId> params) {
            queue.add(params);
        }
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
            if(processor == null) 
            {
                // TODO: Publish event
                observer.completeExceptionally(
                        new Exception(String.format("Processor %s does not exist", "realtime")));
            }

            XtceDb mdb = XtceDbFactory.getInstance(processor.getInstance());
            
//            mdb.getParameter("").get
            
            //This looks kind of ugly, but I don't think there is another way of doing it.
            NamedObjectId id = MdbApi.verifyParameterId(ctx, mdb, "/cfs//sch/SCH_DiagPacket_t.EntryStates_0_");
//
            long timeout = 10000;
            boolean fromCache = true;
//
            List<NamedObjectId> ids = Arrays.asList(id);
            //There is no way for me to get aggregates. Will have assemble them manually...
            List<ParameterValue> pvals = doGetParameterValues(processor, ctx.user, ids, fromCache, timeout);
            
            System.out.println("pvals:" + pvals);
//
//            ParameterValue pval;
//            if (pvals.isEmpty()) {
//                pval = ParameterValue.newBuilder().setId(id).build();
//            } else {
//                pval = pvals.get(0);
//            }
            
//            YamcsClient a;
//            XtceDb mdb = instance.getXtceDb();
//            mdb.getParameter("");
//            System.out.println("diag message from db:"
//                    + instance.getXtceDb().getParameter("/cfs//sch/SCH_DiagPacket_t.EntryStates_0_").getInitialValue());
            
//            System.out.println("diag message from db:"
//                    + instance.getXtceDb().getPa("/cfs//sch/SCH_DiagPacket_t.EntryStates_0_").getInitialValue())
            observer.complete(SchTableResponse.newBuilder().build());
        }

    }

}
