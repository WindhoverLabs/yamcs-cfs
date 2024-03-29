package com.windhoverlabs.yamcs.algorithms;

import java.util.List;
import org.yamcs.algorithms.AbstractAlgorithmExecutor;
import org.yamcs.algorithms.AlgorithmException;
import org.yamcs.algorithms.AlgorithmExecutionContext;
import org.yamcs.algorithms.AlgorithmExecutionResult;
// import org.yamcs.algorithms.AlgorithmExecutionResult;
import org.yamcs.mdb.ProcessingData;
import org.yamcs.parameter.ParameterValue;
import org.yamcs.xtce.Algorithm;
import org.yamcs.xtce.InputParameter;

public class CFETime {

  // Still not sure if we NEED the class to be static(?)
  public static class CFETime_Algorithm extends AbstractAlgorithmExecutor {

    public CFETime_Algorithm(Algorithm algorithmDef, AlgorithmExecutionContext execCtx) {
      super(algorithmDef, execCtx);
    }

    private int CFE_TIME_Sub2MicroSecs(int SubSeconds) {
      // Some cool time calculations

      return 0;
    }

    //	    /**
    //	     * This function is called every time the algorithm is triggered.
    //	     * @param acqTime
    //	     * @param genTime
    //	     * @return
    //	     */
    //	    public AlgorithmExecutionResult execute(long acqTime, long genTime) {
    //	    	/**
    //	    	 * For demo purposes this assumes that inputValues has only one value and that is
    //	    	 * "cfs/cfe_time/CFE_TIME_HkPacket_t"
    //	    	 *
    //	    	 * The inputValues refer to all of the nodes under InputSet in XTCE
    //	    	 */
    //
    //	        AggregateValue v = (AggregateValue) inputValues.get(0).getEngValue();
    //	        AggregateValue payload =  (AggregateValue) v.getMemberValue("Payload");
    //
    //	        //Probably not the cleanest way of fetching values, still learning about the API.
    //	        //Doing something really simple. Take the command counter and multiply it by 2.
    //	        int counter =  (Integer.parseInt(payload.getMemberValue("CmdCounter").toString()) *
    // 2);
    //
    //	        //Do we have to read the YAML form this context? If so, how should we do it?
    //	        //Unless the format is in the packet itself...?
    //	        //Unless we can access the registry from this context?
    //	        int realTime = CFE_TIME_Sub2MicroSecs(1);
    //
    //	        //getOutputParameter refers to the nodes under OutputSet in XTCE.
    //	        ParameterValue pv = new ParameterValue(getOutputParameter(0));
    //	        pv.setEngineeringValue(ValueUtility.getSint32Value(counter));
    //
    //	        return new AlgorithmExecutionResult(Arrays.asList(pv));
    //	    }

    protected void updateInput(int idx, InputParameter inputParameter, ParameterValue newValue) {}

    public List<ParameterValue> runAlgorithm(long acqTime, long genTime) {
      // Not using this one for now because I think is deprecated. Still investigating.
      // TODO Auto-generated method stub
      return null;
    }

    @Override
    public AlgorithmExecutionResult execute(long arg0, long arg1, ProcessingData arg2)
        throws AlgorithmException {
      // TODO Auto-generated method stub
      return null;
    }
  }
}
