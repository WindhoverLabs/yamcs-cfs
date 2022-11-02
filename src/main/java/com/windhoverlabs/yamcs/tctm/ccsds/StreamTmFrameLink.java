package com.windhoverlabs.yamcs.tctm.ccsds;

import org.yamcs.ConfigurationException;
import org.yamcs.TmPacket;
import org.yamcs.YConfiguration;
import org.yamcs.logging.Log;
import org.yamcs.tctm.TcTmException;
import org.yamcs.tctm.ccsds.AbstractTmFrameLink;
import org.yamcs.yarch.ColumnDefinition;
import org.yamcs.yarch.DataType;
import org.yamcs.yarch.Stream;
import org.yamcs.yarch.StreamSubscriber;
import org.yamcs.yarch.Tuple;
import org.yamcs.yarch.TupleDefinition;
import org.yamcs.yarch.YarchDatabase;
import org.yamcs.yarch.YarchDatabaseInstance;

/**
 * Receives telemetry fames via UDP. One UDP datagram = one TM frame.
 *
 * <p>This is a TEMPORARY fix. The proper fix is adding the logic of removing the 4 bytes in
 * simlink.
 *
 * @author nm
 */
public class StreamTmFrameLink extends AbstractTmFrameLink implements StreamSubscriber {
    private volatile int invalidDatagramCount = 0;

    protected Log log;
    
    String packetPreprocessorClassName;
    Object packetPreprocessorArgs;
    Thread thread;
    protected Stream stream;
    protected int frameLength;

    static TupleDefinition gftdef;

    final static String RECTIME_CNAME = "rectime";
    final static String DATA_CNAME = "data";
  
    static
    {
        gftdef = new TupleDefinition();
        gftdef.addColumn(new ColumnDefinition(RECTIME_CNAME, DataType.TIMESTAMP));
        gftdef.addColumn(new ColumnDefinition(DATA_CNAME, DataType.BINARY));
    }

    /**
     * Creates a new UDP Frame Data Link
     *
     * @throws ConfigurationException if port is not defined in the configuration
     */
    public void init(String instance, String name, YConfiguration config)
      throws ConfigurationException 
    {
        super.init(instance, name, config);
        String streamName = config.getString("stream");
        this.frameLength = config.getInt("frameLength");

        YarchDatabaseInstance ydb = YarchDatabase.getInstance(instance);
        this.stream = getStream(ydb, streamName);
        
        log = new Log(getClass(), instance);
        log.setContext(name);
    
        this.stream.addSubscriber(this);
    }
  
  
    private static Stream getStream(YarchDatabaseInstance ydb, String streamName)
    {
        Stream stream = ydb.getStream(streamName);
        if (stream == null)
        {
            try 
            {
                ydb.execute("create stream " + streamName + gftdef.getStringDefinition());
            } 
            catch (Exception e) 
            {
                throw new ConfigurationException(e);
            }
            stream = ydb.getStream(streamName);
        }
      
        return stream;
    }

    
    @Override
    public void doStart() 
    {
        notifyStarted();
    }

    
    @Override
    public void doStop()
    {
        notifyStopped();
    }


    /** returns statistics with the number of datagram received and the number of invalid datagrams */
    @Override
    public String getDetailedStatus()
    {
        if (isDisabled())
        {
            return "DISABLED";
        }
        else
        {
            return String.format(
              "OK %nValid datagrams received: %d%nInvalid datagrams received: %d",
              frameCount.get(), invalidDatagramCount);
        }
    }
    

    @Override
    protected Status connectionStatus()
    {
	    if(isDisabled())
	    {
		    return Status.DISABLED;
	    }
	    else
	    {
            return Status.OK;
	    }
    }
  
  
	@Override
	public void onTuple(Stream arg0, Tuple tuple) 
	{
		if(isRunning())
		{
	        byte[] pktData = tuple.getColumn(DATA_CNAME);
	        long recTime = tuple.getColumn(RECTIME_CNAME);
	        if (pktData == null)
	        {
	            throw new ConfigurationException("no column named '%s' in the tuple", DATA_CNAME);
	        }
	        else
	        {
	        	if(pktData.length != this.frameLength)
	        	{
	        		/* This is not the correct size.  Reject it. */
	                log.error("Packet incorrect length. Expected {}.  Received {}", this.frameLength, pktData.length);
	        	}
	        	else
	        	{
	                TmPacket tmPacket = new TmPacket(recTime, pktData);
	            
    	            tmPacket.setEarthRceptionTime(timeService.getHresMissionTime());

	                try 
	                {
		    			frameHandler.handleFrame(timeService.getHresMissionTime(), pktData, 0, pktData.length);
			    	}
	                catch (TcTmException e)
	                {
					    e.printStackTrace();
			    	}
				}
	        }
		}
	}	
}
