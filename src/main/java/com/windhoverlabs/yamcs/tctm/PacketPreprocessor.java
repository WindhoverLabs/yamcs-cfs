/****************************************************************************
 *
 *   Copyright (c) 2020 Windhover Labs, L.L.C. All rights reserved.
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

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.yamcs.TmPacket;
import org.yamcs.YConfiguration;
import org.yamcs.tctm.AbstractPacketPreprocessor;
import org.yamcs.utils.TimeEncoding;

public class PacketPreprocessor extends AbstractPacketPreprocessor {

  private Map<Integer, AtomicInteger> seqCounts = new HashMap<Integer, AtomicInteger>();

  public PacketPreprocessor(String yamcsInstance) {
    super(yamcsInstance, YConfiguration.emptyConfig());
  }

  @Override
  public TmPacket process(TmPacket packet) {

    byte[] bytes = packet.getPacket();
    if (bytes.length < 6) { // Expect at least the length of CCSDS primary header
      eventProducer.sendWarning(
          "SHORT_PACKET",
          "Short packet received, length: "
              + bytes.length
              + "; minimum required length is 6 bytes.");

      // If we return null, the packet is dropped.
      return null;
    }

    // Verify continuity for a given APID based on the CCSDS sequence counter
    int apidseqcount = ByteBuffer.wrap(bytes).getInt(0);
    int apid = (apidseqcount >> 16) & 0x07FF;
    int seq = (apidseqcount) & 0x3FFF;
    AtomicInteger ai = seqCounts.computeIfAbsent(apid, k -> new AtomicInteger());
    int oldseq = ai.getAndSet(seq);

    if (((seq - oldseq) & 0x3FFF) != 1) {
      eventProducer.sendWarning(
          "SEQ_COUNT_JUMP",
          "Sequence count jump for APID: " + apid + " old seq: " + oldseq + " newseq: " + seq);
    }

    // Our custom packets don't include a secondary header with time information.
    // Use Yamcs-local time instead.
    packet.setGenerationTime(TimeEncoding.getWallclockTime());

    // Use the full 32-bits, so that both APID and the count are included.
    // Yamcs uses this attribute to uniquely identify the packet (together with the gentime)
    packet.setSequenceCount(apidseqcount);

    return packet;
  }
}
