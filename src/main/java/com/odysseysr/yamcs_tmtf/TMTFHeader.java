// Copyright (c) 2018, Odyssey Space Research, LLC.
// (( (Zachary Porter), (Odyssey Space Research), (Summer 2018) ))

package com.odysseysr.yamcs_tmtf;

import java.nio.ByteBuffer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TMTFHeader{
	
	protected Logger log=LoggerFactory.getLogger(this.getClass().getName());
	int TFVersion;
	int SCID;
	int VCID;
	boolean OCFFlag; 
	int MCID;
	int GVID;
	int McFrameCount;
	int VcFrameCount;
	boolean secondaryHeaderFlag;
	boolean syncFlag;
	boolean packetOrderFlag;
	int segmentLengthID;
	int firstHeaderPointer;
	int secondaryHeaderLength = -1;
	boolean noDataInMessage=false;;
	boolean onlyIdleData=false;
	
	public TMTFHeader(byte[] header) {
		ByteBuffer b = ByteBuffer.wrap(header);
		initialize(b);
	}
	public TMTFHeader(ByteBuffer header) {
		initialize(header);
	}
	private void initialize(ByteBuffer b) {
		if (b.array().length != TMTFReader.TMTF_HEADER_LENGTH) {
			log.warn("TMTFHeader.initialize(..) failed. Incorrect buffer length.");
		}
		
		// TODO: Add checks to assure reasonable data was read. 
		// EX: TFVersion can only be within the range of 0-4. ((~0x3F)>>6)
		
		TFVersion = readTFVersion(b);
		SCID = readSCID(b);
		VCID = readVCID(b);
		OCFFlag = readOCFFlag(b);
		MCID = readMCID(b);
		GVID = readGVID(b);
		McFrameCount = readMcFrameCount(b);
		VcFrameCount = readVcFrameCount(b);
		secondaryHeaderFlag = readSecondaryHeaderFlag(b);
		syncFlag = readSyncFlag(b);
		packetOrderFlag = readPacketOrderFlag(b);
		segmentLengthID = readSegmentLengthID(b);
		firstHeaderPointer = readFirstHeaderPointer(b);
	}
	public void initializeSecondaryHeaderLength(byte b) {
		secondaryHeaderLength = readSecondaryHeaderLength(b);
	}
    /**
    Assuming:
    id[0]: stored at 0
    id[1]: stored at 1
    McFrameCount: stored at 2
    VcFrameCount: stored at 3
    DataFieldStatus[0]: stored at 4
    DataFieldStatus[1]: stored at 5
    Frame count: stored at both 6 and 7 (identical 0-0xFF) 
    
   */

	public boolean isDataValid() {
		// CCSDS 132.0-B-2 (4.1.2.2.2)
		if (this.TFVersion != 0) return false;
		// TODO : Implement more checks
		
		return true;
	}
    private int readTFVersion(ByteBuffer message){
        return ((message.get(0) & (~0x3F)) >>> 6);
    }
    private int readSCID(ByteBuffer message){
        return (int)((short)(message.get(0) & (~0xC0)) << 4) |
            ((message.get(1) & (~0x0F)) >>> 4); 
    }
    private int readVCID(ByteBuffer message){
        return ((message.get(1) & (~0xF1)) >>> 1);
    }
    private boolean readOCFFlag(ByteBuffer message){
        return (message.get(1) & (~0x01)) == 1 ? true : false;
    }
    private int readMCID(ByteBuffer message){
        // as implemented in tmtf.c
        return (message.get(0) << 8 | message.get(1) >>> 4);
    }
    private int readGVID(ByteBuffer message){
        // as implemented in tmtf.c
        return (message.get(0) << 8 | message.get(1) & 0xFE);
    }
    private int readMcFrameCount(ByteBuffer message){
        return ((message.get(2) & 0xFF));
    }
    
    private int readVcFrameCount(ByteBuffer message){
        return ((message.get(3) & 0xFF));
    }
    private boolean readSecondaryHeaderFlag(ByteBuffer message){
        return ((message.get(4) & 0x80) >>> 7) == 1 ? true : false;
    }
    private boolean readSyncFlag(ByteBuffer message){
        return ((message.get(4) & 0x40) >>> 6) == 1 ? true : false;
    }
    private boolean readPacketOrderFlag(ByteBuffer message){
        return ((message.get(4) & (~0xDF) >>> 5)) == 1 ? true : false;
    }
    private int readSegmentLengthID(ByteBuffer message){
        return ((message.get(4) & (~0xE7) >>> 3));
    }
    private int readFirstHeaderPointer(ByteBuffer message){
        return ((short)(message.get(4) & (~0xF8)) << 8) | 
            (short)(message.get(5) & 0xFF); 
    }
    private int readSecondaryHeaderLength(byte message){
    	return (message & (0x3F));
    }
    /**
     * FIXME: unimplemented
     * @return 0
     * @deprecated
     */
    private int readCRC(ByteBuffer message){
     return 0;
    }
}

