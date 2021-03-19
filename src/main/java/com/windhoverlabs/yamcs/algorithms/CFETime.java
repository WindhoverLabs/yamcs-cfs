package com.windhoverlabs.yamcs.algorithms;

import java.util.Arrays;
import java.util.List;

import org.yamcs.algorithms.AbstractAlgorithmExecutor;
import org.yamcs.algorithms.AlgorithmExecutionContext;
import org.yamcs.algorithms.AlgorithmExecutionResult;
import org.yamcs.parameter.AggregateValue;
import org.yamcs.parameter.ParameterValue;
import org.yamcs.utils.ValueUtility;
import org.yamcs.xtce.Algorithm;
import org.yamcs.xtce.InputParameter;

public class CFETime {

	 public static class AvgAlgorithm2 extends AbstractAlgorithmExecutor {

	    public AvgAlgorithm2(Algorithm algorithmDef, AlgorithmExecutionContext execCtx) {
	        super(algorithmDef, execCtx);
	    }
	    

	    /**
	     * This function is called every time the algorithm is triggered.
	     * @param acqTime
	     * @param genTime
	     * @return
	     */
	    public AlgorithmExecutionResult execute(long acqTime, long genTime) {
	    	/**
	    	 * For demo purposes this assumes that inputValues has only one value and that is
	    	 * "cfs/cfe_time/CFE_TIME_HkPacket_t"
	    	 * 
	    	 * The inputValues refer to all of the nodes under InputSet in XTCE
	    	 */
	    	
	        AggregateValue v = (AggregateValue) inputValues.get(0).getEngValue();
	        AggregateValue payload =  (AggregateValue) v.getMemberValue("Payload");
	        
	        //Probably not the cleanest way of fetching values, still learning about the API.
	        //Doing something really simple. Take the command counter and multiply it by 2.
	        int counter =  (Integer.parseInt(payload.getMemberValue("CmdCounter").toString()) * 2);

	        //getOutputParameter refers to the nodes under OutputSet in XTCE.
	        ParameterValue pv = new ParameterValue(getOutputParameter(0));
	        pv.setEngineeringValue(ValueUtility.getSint32Value(counter));

	        return new AlgorithmExecutionResult(Arrays.asList(pv));
	    }
	    
	    protected void updateInput(int idx, InputParameter inputParameter, ParameterValue newValue) {
	    }

		@Override
		public List<ParameterValue> runAlgorithm(long acqTime, long genTime) {
			//Not using this one for now because I think is deprecated. Still investigating.
			// TODO Auto-generated method stub
			return null;
		}
	}   

	
}
