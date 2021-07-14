/****************************************************************************
 *
 *   Copyright (c) 2021 Windhover Labs, L.L.C. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions
 * are met:
 *
 * 1. Redistributions of source code must retain the above copyright
 *    notice, this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright
 *    notice, this list of conditions and the following disclaimer in
 *    the documentation and/or other materials provided with the
 *    distribution.
 * 3. Neither the name Windhover Labs nor the names of its
 *    contributors may be used to endorse or promote products derived
 *    from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS
 * FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE
 * COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT,
 * INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING,
 * BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS
 * OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED
 * AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT
 * LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN
 * ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 *
 *****************************************************************************/

package com.windhoverlabs.yamcs.tctm;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;

import org.yamcs.YConfiguration;
import org.yamcs.tctm.PacketInputStream;

/**
 * Reads CCSDS packets from an input stream:
 * first it reads 6 bytes primary header, it derives the length from the last two bytes and reads the remaining of the
 * data.
 * 
 * It also support a maxLength property to limit the size of the packet that is being read.
 * 
 * @author Mathew Benson
 *
 */
public class CcsdsPacketInputStream implements PacketInputStream {
    DataInputStream dataInputStream;
    int maxPacketLength    = 32768;
    boolean secHdrRequired = false;
    boolean segAllowed     = false;
    boolean tlmOnly        = false;
    
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
        this.tlmOnly         = args.getBoolean("tlmOnly", tlmOnly);
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
    		        
    		        /* If the telemetry only is selected, check it. */
    		        if(this.tlmOnly) {
    		        	/* Telemetry only. */
    		            if((hdr[0] & 0x10) != 0) {
    		        	    /* The downlink should contain telemetry only. 
    		        	     * The stream is out of sync so remain in this 
    		        	     * state. */
    		        	    break;
    		            }
    		        }
    		        
    		        /* If the Secondary Header is required, check it. */
    		        if(this.secHdrRequired) {
    		        	/* It is required. */
    		            if((hdr[0] & 0x08) != 0x08) {
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
    		            if((hdr[2] & 0xc0) != 0xc0) {
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
