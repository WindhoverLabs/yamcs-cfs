// Copyright (c) 2018, Odyssey Space Research, LLC.
// (( (Zachary Porter), (Odyssey Space Research), (Summer 2018) ))
package com.odysseysr.yamcs_tmtf;

/**
 * Note to the future:
 * This enum set is not currently used and was added because it 
 * originally theorized to be important. It has undergone no testing
 * and has not been linked to any other TMTF class. Feel free to delete
 * the class if it is decided to be irrelevant or obsolete.It has been
 * kept simply because it would be used for any future expansion of the 
 * protocol or addition of multiplexing.  
 * 
 * 
 * This enum defines all the services offered by the TM Transfer Frame
 * Protocol. The set of available options is quite limited and it is highly
 * advised to throughly understand all of the services and their restrictions
 * before defining which ones you need to use.
 * 
 * 
 * VC indicates Virtual Channel
 * MC indicates Master Channel
 * 
 * 
 * Requirements: (CCSDS 132.0-B-2 (2.2.4))
 *  a) If the master channel frame service exists on a master channel, 
 *  other services shall not exist simultaneously on that master channel
 *  
 *  b) On one master channel, the virtual channel frame secondary header
 *  shall not exist simultaneously with the master channel frame secondary header service
 *  
 *  c) on one master channel, the virtual channel operational control field service shall not
 *  exist simultaneously with the master channel operational control field service
 *  
 *  d) if the virtual channel frame service exists on a virtual channel,
 *  other services shall not exist simultaneously on that virtual channel
 *  
 *  e) on one virtual channel, the virtual channel packet service shall not 
 *  exist simultaneously with the virtual channel access service
 * 
 * @author zporter
 * @see (CCSDS 132.0-B-2 (2.2.3))
 *
 */
public enum TMTFServiceOption {
	VC_PACKET(true),
	VC_ACCESS(false),
	VC_FRAME_SECONDARY_HEADER(true),
	VC_OPERATIONAL_CONTROL_FIELD(true),
	VC_FRAME(false),
	MC_FRAME_SECONDARY_HEADER(false),
	MC_OPERATIONAL_CONTROL_FIELD(false),
	MC_FRAME(true),
	
;
	public boolean enabled;
	private TMTFServiceOption(boolean enabled) {
		this.enabled=enabled;
	}
	public boolean isValidConfiguration() {
		// A
		if (MC_FRAME.enabled) {
			if (MC_FRAME_SECONDARY_HEADER.enabled) return false;
			if (MC_OPERATIONAL_CONTROL_FIELD.enabled) return false;
		}
		// B
		if (VC_FRAME_SECONDARY_HEADER.enabled 
				&& MC_FRAME_SECONDARY_HEADER.enabled) return false;
		
		// C
		if (VC_OPERATIONAL_CONTROL_FIELD.enabled && 
				MC_OPERATIONAL_CONTROL_FIELD.enabled) return false;
		
		// D
		if (VC_FRAME.enabled) {
			if (VC_PACKET.enabled) return false;
			if (VC_ACCESS.enabled) return false;
			if (VC_FRAME_SECONDARY_HEADER.enabled) return false;
		}
		
		// E
		if (VC_PACKET.enabled && VC_ACCESS.enabled) return false;
		
		return true;
	}
}
