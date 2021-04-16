package org.yamcs.tctm;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;

import org.yamcs.YConfiguration;

/**
 * Reads CCSDS packets from an input stream:
 * first it reads 6 bytes primary header, it derives the length from the last two bytes and reads the remaining of the
 * data.
 * 
 * It also support a maxLength property to limit the size of the packet that is being read.
 * 
 * @author nm
 *
 */
public class CcsdsPacketInputStream implements PacketInputStream {
    DataInputStream dataInputStream;
    int maxPacketLength = 32768;
    boolean secHdrRequired = false;
    boolean segAllowed = false;
    
    enum ParserState {
    	WAITING_FOR_BYTE_1,
    	WAITING_FOR_BYTE_3,
    	MESSAGE_COMPLETE
    }
    
    ParserState parserState;

    @Override
    public void init(InputStream inputStream, YConfiguration args) {
        this.dataInputStream = new DataInputStream(inputStream);
        this.maxPacketLength = args.getInt("maxPacketLength", maxPacketLength);
        this.secHdrRequired  = args.getBoolean("secHdrRequired", secHdrRequired);
        this.segAllowed      = args.getBoolean("segAllowed", segAllowed);
    }

    @Override
    public byte[] readPacket() throws IOException {
        byte[] packet = null;
        byte[] hdr = new byte[6];

        parserState = ParserState.WAITING_FOR_BYTE_1;
        
    	while(parserState != ParserState.MESSAGE_COMPLETE) {
    		switch(parserState) {
    		    case WAITING_FOR_BYTE_1: {
    		    	/* Read one byte only. */
    		        dataInputStream.readFully(hdr, 0, 1);
    		        /* Check the version ID. */
    		        if((hdr[0] & 0xe0) != 0) {
    		        	/* The version ID must be 0. The stream is out of 
    		        	 * sync so remain in this state. */
    		        	break;
    		        }
    		        
    		        /* If the Secondary Header is required, check it. */
    		        if(this.secHdrRequired) {
    		        	/* It is required. */
    		            if((hdr[0] & 0x08) != 1) {
    		        	    /* The secondary header is required but is not 
    		        	     * present.  The stream is out of sync so remain 
    		        	     * in this state. */
    		        	    break;
    		            }
    		        }

    		        /* Nothing to validate in the next word. Just read it. */
    		        dataInputStream.readFully(hdr, 1, 1);
    		        parserState = ParserState.WAITING_FOR_BYTE_3;
    		        break;
    		    }	
    		    
    		    case WAITING_FOR_BYTE_3: {
    		        dataInputStream.readFully(hdr, 2, 1);
    		        /* If the segmentation is not allowed, check the 
    		         * segmentation flags. */
    		        if(this.segAllowed == false) {
    		        	/* It is not allowed. */
    		            if((hdr[0] & 0xc0) != 0) {
    		        	    /* The segmentation flags must be 3 (complete packet). 
    		        	     * The stream is out of sync, so fall back to the 
    		        	     * initial state. */
        		            parserState = ParserState.WAITING_FOR_BYTE_1;
    		        	    break;
    		            }
    		        }

    		        /* Nothing to validate for the rest of the message. 
    		         * Just read the rest of the header.*/
    		        dataInputStream.readFully(hdr, 3, 3);
    		        
    		        /* Calculate how many more bytes are remaining. */
    		        int remaining = ((hdr[4] & 0xFF) << 8) + (hdr[5] & 0xFF) + 1;
    		        int pktLength = remaining + hdr.length;
    		        
    		        if (pktLength > maxPacketLength) {
    		            throw new IOException("Invalid packet read: "
    		                    + "packetLength (" + pktLength + ") > maxPacketLength(" + maxPacketLength + ")");
    		        }

    		        packet = new byte[pktLength];
    		        
    		        System.arraycopy(hdr, 0, packet, 0, hdr.length);
    		        dataInputStream.readFully(packet, hdr.length, remaining);
    		        
    		        /* We've read a complete message. Transition to complete
    		         * so the parser will terminate and return the message.
    		         */
    		        parserState = ParserState.MESSAGE_COMPLETE;
    		        break;
    		    }	
    		}
    	}
    	
        return packet;
    }

    @Override
    public void close() throws IOException {
        dataInputStream.close();
    }
}
