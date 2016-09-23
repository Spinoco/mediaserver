/*
 * JBoss, Home of Professional Open Source
 * Copyright 2011, Red Hat, Inc. and individual contributors
 * by the @authors tag. See the copyright.txt in the distribution for a
 * full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.mobicents.media.server.impl.rtp;

import static org.junit.Assert.assertEquals;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * 
 * @author kulikov
 * @author amit bhayani
 */
public class RtpPacketTest {

    // todo: fix
    private RtpPacket rtpPacket()  {
        return RtpPacket.fromRaw(local,remote,p,0,p.length);
    }

    private InetSocketAddress local = new InetSocketAddress(7777);
    private InetSocketAddress remote = new InetSocketAddress(7778);

    //These values are from wireshark trace
    private byte[] p = new byte[]{(byte) 0x80, 0x08, 0x6a, 0x6c, (byte) 0xc1, (byte) 0xab, 0x74, (byte) 0x8d,
        (byte) 0xb2, (byte) 0xe2, (byte) 0x83, 0x69, 0x57, 0x57, 0x57, 0x57, 0x57, 0x57, 0x54, 0x54, 0x54, 0x54,
        0x54, 0x54, 0x54, 0x54, 0x54, 0x54, 0x54, 0x54, 0x54, 0x54, 54, 0x54, 0x54, 0x54, 0x54, 0x54, 0x54, 0x54,
        0x54, 0x54, 0x54, 0x54, 0x54, 0x54, 0x54, 0x54, 0x54, 0x54, 0x54, 0x54, 0x54, 0x54, 0x54, 0x54, 0x54, 0x54,
        0x54, 0x54, 0x54, 0x54, 0x54, 0x54, 0x54, 0x54, 0x54, 0x54, 0x54, 0x54, 0x54, 0x54, 0x54, 0x54, 0x54, 0x54,
        0x54, 0x54, 0x54, 0x54, 0x54, 0x54, 0x54, 0x54, 0x54, 0x54, 0x54, 0x54, 0x54, 0x54, 0x54, 0x54, 0x54, 0x54,
        0x54, 0x54, 0x54, 0x54, 0x54, 0x54, 0x54, 0x54, 0x54, 0x54, 0x54, 0x54, 0x54, 0x54, 0x54, 0x54, 0x54, 0x54,
        0x54, 0x54, 0x54, 0x54, 0x54, 0x54, 0x54, 0x54, 0x54, 0x54, 0x54, 0x54, 0x54, 0x54, 0x54, 0x54, 0x54, 0x54,
        0x54, 0x54, 0x54, 0x54, 0x54, 0x54, 0x54, 0x54, 0x54, 0x54, 0x54, 0x54, 0x54, 0x54, 0x54, 0x54, 0x54, 0x54,
        0x54, 0x54, 0x54, 0x54, 0x54, 0x54, 0x54, 0x54, 0x54, 0x54, 0x54, 0x54, 0x54, 0x54, 0x54, 0x54, 0x54, 0x54,
        0x54, 0x54, 0x54, 0x54, 0x54, 0x54};

    public RtpPacketTest() {
    }

    @BeforeClass
    public static void setUpClass() throws Exception {
    }

    @AfterClass
    public static void tearDownClass() throws Exception {
    }


    @After
    public void tearDown() {
    }

    @Test
    public void testGetMarker() {
        assertEquals(false, rtpPacket().getMarker());
    }

    @Test
    public void testGetPayloadType() {
        assertEquals(8, rtpPacket().getPayloadType());
    }

    @Test
    public void testGetSequenceNumber() {
        assertEquals(27244, rtpPacket().getSeqNumber());
    }

    @Test
    public void testGetTimestamp() {
        assertEquals(3249239181l, rtpPacket().getTimestamp());
    }

    @Test
    public void testSyncSource() {
        assertEquals(3001189225l, rtpPacket().getSyncSource());
    }

    @Test
    public void testGetPayloadLength() {
        RtpPacket packet = rtpPacket();
        assertEquals(p.length - 12, packet.getPayloadLength());
        packet.getTimestamp();
        assertEquals(p.length - 12, packet.getPayloadLength());
    }

    @Test
    public void testWrap() {
        RtpPacket packet = RtpPacket.outgoing(local,remote,false, 8, 27244, 3249239181l, 3001189225l, p, 12, p.length - 12);
        ByteBuffer buffer = ByteBuffer.wrap(p);
        assertEquals(0, buffer.compareTo(ByteBuffer.wrap(packet.toArray())));
    }

    @Test
    public void testMark() {
        RtpPacket packet = RtpPacket.outgoing(local,remote,true, 8, 27244, 3249239181l, 3001189225l, p, 12, p.length - 12);
        assertEquals(true, packet.getMarker());
    }

    @Test
    public void testWrapTime() {
        long s = System.nanoTime();
        RtpPacket packet = null;


        for (int i = 0; i < 1000; i++) {
            packet = RtpPacket.outgoing(local,remote,false, 8, 27244, 3249239181l, 3001189225l, p, 12, p.length - 12);
        }
        long d = System.nanoTime() - s;
        System.out.println("Execution time=" + d);
    }

}
