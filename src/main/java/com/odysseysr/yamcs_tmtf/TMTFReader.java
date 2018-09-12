// Copyright (c) 2018, Odyssey Space Research, LLC.
// (( (Zachary Porter), (Odyssey Space Research), (Summer 2018) ))

package com.odysseysr.yamcs_tmtf;
import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.InputStreamReader;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This library is designed to take apart frames assembled via the
 * io_lib and to_osr libraries. These follow the standards outlined at:
 * 
 * 
 * This reader does NOT contain specialized support for any of the following services:
 * - VCP
 * - VCA
 * - VC_FSH
 * - VC_OCF
 * - MC_FSH
 * - MC_OCF
 * - MCF
 * - SDLS
 * It simply decodes the incoming (VCF) frames and does not allow for any advanced 
 * synchronized or async communications. It is designed to use a SINGLE Master Channel
 * that is exactly akin to its Physical Channel. (This can be considered periodic as per
 * CCSDS 132.0-B-2 (2.2.2.4 (b)))
 * 
 * It is easily possible to add support for multiple virtual channels via addition of a:
 * channel multiplexer implemented higher up, and addition of a VC identifier in this class.
 * So far this has been implemented to just listen to virtual channel information 
 * 
 * Assumptions:
 * 	- Received frame can be any size greater than 12 octets (for both header types), and less than the maxsize of an int
 *  - Received frame should not have a secondary header. 
 *  			(will skip over any data contained within)
 *  - Received frame should only contain CCSDS space packet data as defined in the Space Packet Protocol
 *  - Incoming messages should have a PVN of '000' as defined by SANA
 *  - If OCF is enabled, the Frame Error Control Field (FECF) is also enabled. 
 *  - The Virtual Channel Frame (VCF) frame count is only reset when it reaches 0xFF, 
 *  		A skip in the count indicates a lost frame and any previous partial data is lost. 
 *  - The synchronization flag must be 0. As stated above, VCA is not supported. 
 *  - The FECF (Frame Error Control Field) flag must be manually set to reflect that on the frame-sender. Default:false
 * 	- The MAX_MESSAGE_LENGTH field must be set manually. This should never need to be used, but would minimize the damage
 * 			if the loop got out of sync with the frame. Without this, a worst case of randomly getting a length of 0xFFFF
 * 			would be devastatingly bad -- hundreds of messages lost. Whereas only loosing a frame is bad, but it is less-bad.
 * 			Default: 5000
 *  
 * Important documentation:
 * 
 * Space Packet Protocol :
 *  https://public.ccsds.org/Pubs/133x0b1c2.pdf
 *  
 * @see CCSDS Space Packet Protocol Documentation 
 * 		Figure 4-1 Space Packet Structural components
 * 		Figure 4-2 Packet Primary Header
 * 		4.1.2.3.4.4 APID idle packet 
 *  
 *  
 * TM Space Data Link Protocol:
 *  https://public.ccsds.org/Pubs/132x0b2.pdf
 * 
 * (Links working as of August 2018)
 *  
 *   
 *  
 * @author zporter
 * @see io_lib/../tmtf.c
 * 
 */
public class TMTFReader {
    // All of these variables are read in cfs.yaml and should be treated
    // like they are final (as indicated by the all-capitalized name)
    // Lengths of each header definition
    public static int TMTF_HEADER_START = 4;
    public static int TMTF_HEADER_LENGTH = 6;
    public static int CCSDS_HEADER_LENGTH = 6;
    public static int OCF_LENGTH = 4;
    public static int FECF_LENGTH = 2;
    // must be the same as per the implementation on the framer. 
    public static boolean FECF_FLAG = false;
    public static int MAX_MESSAGE_LENGTH = 5000;

    protected Logger log = LoggerFactory.getLogger(this.getClass().getName());

    boolean process = true;

    /* Constructor. */
    public TMTFReader() {
    
    }
    
    // Whether the previous message had overflow into the next frame
    private boolean hadOverflow = false;
    // Incomplete data present in previous frame
    private byte[] pastMessage = null;
    // Length needed to complete the packet (as outlined in its prior message)
    private int lengthNeeded = 0;
    // Count of the last Vc Frame (used for preventing frame loss) 
    private int lastVcFrameCount = -1;
    // Count of total frames processes (used for initialization purposes)
    private int totalFrameCount = 0;
    // Determines whether the prior message had a CCSDS header that was placed at the end of the message
    private boolean hadCCSDSHeaderOverflow = false;

    public ArrayList<byte[]> deframeFrame(byte[] message, int messagelength) {
        ArrayList<byte[]> packets = new ArrayList<byte[]>();
        // Initialize the header based off the first 6 octets of the message
        TMTFHeader header = createHeader(message);
        
        if (!ensureValidFrame(message,messagelength, header)) 
        {
            return packets;
        }

        if (hasMissedPackets(header)) 
        {
            return null;
        }
        this.totalFrameCount++;

        byte[] data = getDataFromMessage(message, messagelength);
        int datalength = messagelength;
        datalength -= this.getServicesLength(header);
        int pos = 0;
        pos = this.moveBySecondaryHeader(pos, header, data);
        
        if (!hadOverflow) {
            pastMessage = null;
            pos = header.firstHeaderPointer;
        }
        datalength -= TMTF_HEADER_START;
        
        int length;
        int endpos;
        while (pos < datalength) {
            if (pos+(CCSDS_HEADER_LENGTH+1) >= datalength) {
                this.hadCCSDSHeaderOverflow = true;
                this.hadOverflow = true;
                this.lengthNeeded = datalength - pos;
                this.pastMessage = grabFromArray(data, pos, datalength);
                return packets;
            }
            if (this.hadCCSDSHeaderOverflow) {
                data = combineArrays(pastMessage, data);
                pos = 0;
                pos = this.moveBySecondaryHeader(pos, header, data);
                datalength += pastMessage.length;
                this.hadCCSDSHeaderOverflow = false;
                this.pastMessage = null;
                this.hadOverflow = false;
            }
            length = getPacketLength(pos, data, datalength);
            if (length < 1 || length > MAX_MESSAGE_LENGTH) {
                log.warn("Invalid message length. Larger than MAX_MESSAGE_LENGTH: " + length);
            }
            endpos = pos + length;
            if (endpos > datalength) {
                hadOverflow = true;
                lengthNeeded = endpos - datalength;
                if (pastMessage != null) {
                    pastMessage = combineArrays(pastMessage, grabFromArray(data, pos, endpos - lengthNeeded));
                }else {
                    pastMessage = grabFromArray(data, pos, endpos - lengthNeeded);
                }
                return packets;
            }else {
                lengthNeeded = 0;
                hadOverflow = false;
            }
            byte[] packet = createPacket(pos, data, endpos, datalength);
            //if (!isIdlePacket(packet)) {
                packets.add(packet);
            //}
            pos = endpos;
        }
        lengthNeeded = 0;
        hadOverflow = false;
        return packets;
    }

    public int getServicesLength(TMTFHeader header) {
        int length = 0;
        length += (TMTF_HEADER_START+TMTF_HEADER_LENGTH);
        if (header.OCFFlag) {
            length += OCF_LENGTH;
        }
        if (FECF_FLAG) {
            length += FECF_LENGTH;
        }
        return length;
    }

    public boolean hasMissedPackets(TMTFHeader header) {
        if (totalFrameCount == 0 ) {
            this.lastVcFrameCount = header.VcFrameCount;
        }else {
            if (header.VcFrameCount != lastVcFrameCount + 1) {
                return true;
            }
            this.lastVcFrameCount = header.VcFrameCount == 255 ? -1 : header.VcFrameCount;
        }
        return false;
    }

    public boolean ensureValidFrame(byte[] message, int messagelength, TMTFHeader header) {
        if (message.length < (TMTF_HEADER_LENGTH + CCSDS_HEADER_LENGTH)) {
            log.warn("Invalid message recived. Length: " + messagelength);
            return false;
        }
        // Packet must have data
        if (header.noDataInMessage || header.onlyIdleData) 
        {
            return false;
        }
        // sync flag must be 0
        if (header.syncFlag) 
        {
            return false;
        }
        // TODO: implement more validity checks
        return true;
    }

    public byte[] createPacket(int pos, byte[] data, int endpos, int datalength){
        byte[] packet;
        if (pastMessage != null) {
            packet = combineArrays(pastMessage, grabFromArray(data, pos, endpos));
            pastMessage = null;
            hadOverflow = false;
            lengthNeeded = 0;
        } else {
            packet = grabFromArray(data, pos, endpos);
        }
        return packet;
    }

    /**
     * 
     * @param pos suspected start of the header (current position in frame)
     * @param data frame payload
     * @param datalength total length of the data (excludes start and end)
     * @return
     */
    public int getPacketLength(int pos, byte[] data, int datalength) {
        int length;
        
        if (hadOverflow) {
            length = lengthNeeded;
        }else {
            byte[] packetHeader = grabFromArray(data, pos, pos + CCSDS_HEADER_LENGTH);
            length = readCCSDSLength(packetHeader);
            length += CCSDS_HEADER_LENGTH;
        }
        return length;
    }

    public int moveBySecondaryHeader(int pos, TMTFHeader header, byte[] data) {
        if (header.secondaryHeaderFlag) {
            log.warn("CfsTMTFReader is not prepared to deal with secondary header. Header Ignored.");
            header.initializeSecondaryHeaderLength(data[TMTF_HEADER_LENGTH]);
            pos += header.secondaryHeaderLength;
        }
        return pos;
    }

    /**
     * This defines a 16 bit (1 - 65536) number that represents
     * the length of the value. This deals with the +1 to the value of
     * the member. 
     * 
     * @see CCSDS Space Packet Protocol (4.1.2.5)
     * @param pheader header for space packet
     */
    public int readCCSDSLength(byte[] pheader) {
        int val = 0;
        val = val | pheader[4] & 0xFF;
        val = val << 8;
        val = val | pheader[5] & 0xFF;
        val += 1;
        return val;
    }

    /**
     *  An idle packet is defined by one whose APID is all ones.
     *  The APID (Application Process Identifier) is 11 bits long
     *  
     * @see CCSDS Space Packet Protocol (4.1.2.3.4.4)
     * @param pHeader header for space packet
     */
    public boolean isIdlePacket(byte[] pHeader) {
        int APID = 0;
        APID = pHeader[0] & 0x07;
        APID<<=8;
        APID = APID | pHeader[1];
        return (APID == (2^11 - 1));
    }

    public TMTFHeader createHeader(byte[] message) {
        byte[] header = new byte[TMTF_HEADER_LENGTH];
        for (int i = 0; i < TMTF_HEADER_LENGTH; i++) {
            header[i]=message[i + TMTF_HEADER_START];
        }
        return new TMTFHeader(header);
    }

    public byte[] getDataFromMessage(byte[] message, int messagelength) {
        byte[] data = new byte[message.length - TMTF_HEADER_LENGTH];
        for (int x = 0; x < messagelength; x++) {
            data[x] = message[x + TMTF_HEADER_START + TMTF_HEADER_LENGTH];
        }
        return data;
    }

    public byte[] combineArrays(byte[] a, byte[] b) {
        byte[] result = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, result, a.length, b.length); 
        return result;
    }

    public byte[] grabFromArray(byte[] arry, int start, int end) {
        return Arrays.copyOfRange(arry, start, end);
    }

}
