package com.odysseysr.proteus.yamcs.tctm;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;

import org.yamcs.utils.CcsdsPacket;
import org.yamcs.utils.TimeEncoding;
import com.odysseysr.proteus.yamcs.tctm.CfsUdpTmProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class CfsTlmPacket implements Comparable<CfsTlmPacket>
{
    protected int DATA_OFFSET=14;
    protected int TIME_LENGTH=4;
    static Logger log=LoggerFactory.getLogger(CfsTlmPacket.class.getName());
    static public final int MAX_CCSDS_SIZE=1500;
    protected ByteBuffer bb;
	
    public CfsTlmPacket( byte[] packet) 
    {
        CfsUdpTmProvider.CfeTimeStampFormat timestampFormat = CfsUdpTmProvider.getTimeStampFormat();
        
        switch(timestampFormat) {
            case CFE_SB_TIME_32_16_SUBS:
                this.DATA_OFFSET = 12;
                this.TIME_LENGTH = 6;
                break;
				
            case CFE_SB_TIME_32_32_SUBS:
                this.DATA_OFFSET = 14;
                this.TIME_LENGTH = 8;
                break;
				
            case CFE_SB_TIME_32_32_M_20:
                this.DATA_OFFSET = 14;
                this.TIME_LENGTH = 8;
                break;
        }
        
        bb = ByteBuffer.wrap(packet);
    }

    public CfsTlmPacket(ByteBuffer bb) 
    {
        CfsUdpTmProvider.CfeTimeStampFormat timestampFormat = CfsUdpTmProvider.getTimeStampFormat();
        
        switch(timestampFormat) {
            case CFE_SB_TIME_32_16_SUBS:
                this.DATA_OFFSET = 12;
                this.TIME_LENGTH = 6;
                break;
				
            case CFE_SB_TIME_32_32_SUBS:
                this.DATA_OFFSET = 14;
                this.TIME_LENGTH = 8;
                break;
				
            case CFE_SB_TIME_32_32_M_20:
                this.DATA_OFFSET = 14;
                this.TIME_LENGTH = 8;
                break;
        }

        this.bb=bb;
    }

    public int getSecondaryHeaderFlag() 
    {
        return (bb.getShort(0)>>11)&1;
    }

    public int getSequenceCount() 
    {
        return bb.getShort(2)&0x3FFF;
    }
	
    public void setSequenceCount(short seqCount) 
    {
        short oldSeqField = bb.getShort(2);
        short seqInd = (short)(oldSeqField&(~0x3FFF));
        bb.putShort(2, (short)((seqCount&0x3FFF) + seqInd));
    }

    public static short getSequenceCount(ByteBuffer bb) 
    {
        return (short) (bb.getShort(2)&0x3FFF);
    }

    public int getAPID() 	
    {
        return bb.getShort(0)& 0x07FF;
    }
	
    public void setAPID(int apid) 
    {
        int tmp=bb.getShort(0) & (~0x07FF);
        tmp=tmp|apid;
        bb.putShort(0,(short)tmp);
    }	

    public static short getAPID(ByteBuffer bb) 
    {
        return (short)(bb.getShort(0)& 0x07FF);
    }
	
    /*returns the length written in the ccsds header*/
    public static int getCcsdsPacketLength(ByteBuffer bb) 
    {
        return bb.getShort(4)&0xFFFF;
    }
    
    /*returns the length written in the ccsds header*/
    public int getCcsdsPacketLength() 
    {
        return getCcsdsPacketLength(bb);
    }
	
    public void setCcsdsPacketLength(short length) 
    {
        //return bb.getShort(4)&0xFFFF;
        bb.putShort(4, length);
    }
	
    /**returns the length of the packet, normally equals ccsdslength+7*/
    public int getLength() 
    {
        return bb.capacity();
    }
	
    /**
     * 
     * @return instantT
     */
    public long getInstant() 
    {
        /* Return type represents microseconds after GPS epoch.  Convert course and fine time to this. */


        /**
         * we assume coarseTime to be always positive (corresponding to uint32_t in
         * C)
         * @param coarseTime number of seconds from GPS epoch
         * @param fineTime number of 1/256 seconds
         * @return
         */

        long instant = 0;
        int coarse = getCoarseTime();
        byte fine = getFineTime();
        
        instant = TimeEncoding.fromGpsCcsdsTime(coarse, fine) ;
        return instant;
    }

    public static long getInstant(ByteBuffer bb) 
    {
        long instant = 0;
        int coarse = getCoarseTime(bb);
        byte fine = getFineTime(bb);
        
        instant = TimeEncoding.fromGpsCcsdsTime(coarse, fine) ;
        return instant;
    }

    /**
     * 
     * @param data.bb
     * @return time in seconds since 6 Jan 1980
     */
    public int getCoarseTime() 
    {
        //return bb.getInt(6)&0xFFFFFFFFL;
        int coarseTime = 0;
		
        coarseTime = bb.get(10);
        coarseTime = coarseTime + (bb.get(11) << 8);
        coarseTime = coarseTime + (bb.get(12) << 16);
        coarseTime = coarseTime + (bb.get(13) << 24);

        return coarseTime;
    }

    public int getTimeId() 
    {
        return (bb.getChar(11) &0xFF)>>6;
    }
	
    public void setCoarseTime(int time) 
    {
        bb.putInt(6, time);
    }
	
    public static int getCoarseTime(ByteBuffer bb) 
    {
        //return bb.getInt(6)&0xFFFFFFFFL;
        int coarseTime = 0;

        coarseTime = bb.get(10);
        coarseTime = coarseTime + (bb.get(11) << 8);
        coarseTime = coarseTime + (bb.get(12) << 16);
        coarseTime = coarseTime + (bb.get(13) << 24);

        return coarseTime;
    }
	
    public byte getFineTime() 
    {
        byte fineTime = 0;
        CfsUdpTmProvider.CfeTimeStampFormat timestampFormat = CfsUdpTmProvider.getTimeStampFormat();
        
        switch(timestampFormat) {
            case CFE_SB_TIME_32_16_SUBS: {
                fineTime = bb.get(15);
                break;
            }
        		
            case CFE_SB_TIME_32_32_SUBS: {
                fineTime = bb.get(17);
                break;
            }
        		
            case CFE_SB_TIME_32_32_M_20: {
                short tmp = bb.get(16);
                fineTime = (byte)((tmp >> 4) & 0x00ff);
                break;
            }
        }

        return fineTime;
    }
	
    public void setFineTime(short fineTime) 
    {
        byte bFineTime = 0;
        CfsUdpTmProvider.CfeTimeStampFormat timestampFormat = CfsUdpTmProvider.getTimeStampFormat();
        
        switch(timestampFormat) {
            case CFE_SB_TIME_32_16_SUBS: {
                short shortFineTime = bb.getShort(14); // & (short)0xFFFF;
                float fltFineTime = shortFineTime / (short)65536;
                bFineTime = (byte)(fltFineTime * 256);
                break;
            }
	        		
            case CFE_SB_TIME_32_32_SUBS: {
                long lngFineTime = bb.getInt(14) & 0xFFL;
                float fltFineTime = lngFineTime / 4294967296L;
                bFineTime = (byte)(fltFineTime * 256);
                break;
            }
	        		
            case CFE_SB_TIME_32_32_M_20: {
                long lngFineTime = bb.getInt(14) & 0xFFFFFFFFL;
                float fltFineTime = lngFineTime / 4294967296L;
                bFineTime = (byte)(fltFineTime * 256);
                break;
            }
        }
        
        bb.putInt(10, (byte)(fineTime&0xFF));
    }
	
    public static byte getFineTime(ByteBuffer bb) 
    {
        byte fineTime = 0;
        CfsUdpTmProvider.CfeTimeStampFormat timestampFormat = CfsUdpTmProvider.getTimeStampFormat();
        
        switch(timestampFormat) {
            case CFE_SB_TIME_32_16_SUBS: {
                fineTime = bb.get(15);
                break;
            }

            case CFE_SB_TIME_32_32_SUBS: {
                fineTime = bb.get(17);
                break;
            }
        		
            case CFE_SB_TIME_32_32_M_20: {
                short tmp = bb.get(16);
                fineTime = (byte)((tmp >> 4) & 0x00ff);
                break;
            }
        }
	return fineTime;
    }

    public byte[] getBytes() 
    {
        return bb.array();
    }
	
    public ByteBuffer getByteBuffer() 
    {
        return bb;
    }

    public static CcsdsPacket getPacketFromStream(InputStream input) throws IOException 
    {
        byte[] b=new byte[6];
        ByteBuffer bb=ByteBuffer.wrap(b);
        if ( input.read(b) < 6 ) 
        {
            throw new IOException("cannot read CCSDS primary header\n");
        }
        int ccsdslen = bb.getShort(4)&0xFFFF;
        if ( ccsdslen  > MAX_CCSDS_SIZE ) 
        {
            throw new IOException("illegal CCSDS length "+ ccsdslen);
        }
        bb=ByteBuffer.allocate(ccsdslen+7);
        bb.put(b);

        if ( input.read(bb.array(), 6, ccsdslen+1) < ccsdslen+1 ) 
        {
            throw new IOException("cannot read full packet");
        }
        return new CcsdsPacket(bb);
    }
	
	
    public static long getInstant(byte[] pkt) 
    {
        return getInstant(ByteBuffer.wrap(pkt));
    }
	
		
    public static short getAPID(byte[] packet) 
    {
        return getAPID(ByteBuffer.wrap(packet));
    }
	
    public static int getCccsdsPacketLength(byte[] buf) 
    {       
        return getCcsdsPacketLength(ByteBuffer.wrap(buf));
    }

    /*comparison based on time*/
    public int compareTo(CfsTlmPacket p) 
    {
        return Long.signum(this.getInstant()-p.getInstant());
    }
	
    @Override
    public String toString() 
    {
        StringBuffer sb=new StringBuffer();
        StringBuffer text = new StringBuffer();
        byte c;
        int len = bb.limit();
        sb.append("apid: "+getAPID()+"\n");
        sb.append("time: "+ TimeEncoding.toCombinedFormat(getInstant()));
        sb.append("\n");
        for(int i=0;i<len;++i) 
        {
            if(i%16==0) 
            {
                sb.append(String.format("%04x:",i));
                text.setLength(0);
            }
            c = bb.get(i);
            if ( (i & 1) == 0 ) 
            {
                sb.append(String.format(" %02x",0xFF&c));
            } 
            else 
            {
                sb.append(String.format("%02x",0xFF&c));
            }
            text.append(((c >= ' ') && (c <= 127)) ? String.format("%c",c) : ".");
            if((i+1)%16==0) 
            {
                sb.append(" ");
                sb.append(text);
                sb.append("\n");
            }
        }
        sb.append("\n\n");
        return sb.toString();
    }
}

