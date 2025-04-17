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

import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.Random;
import java.util.HashMap;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mobicents.media.server.io.sdp.format.AVProfile;
import org.mobicents.media.server.scheduler.PriorityQueueScheduler;
import org.mobicents.media.server.spi.memory.Frame;

import static org.junit.Assert.*;

/**
 *
 * kulikov
 */
public class JitterBufferTest {
    
    private MockWallClock wallClock = new MockWallClock();
    private RtpClock rtpClock = new RtpClock(wallClock);

    private InetSocketAddress local = new InetSocketAddress(7777);
    private InetSocketAddress remote = new InetSocketAddress(7778);

    private int period = 20;
    private int jitter = 40;

    private JitterBuffer jitterBuffer = new JitterBuffer(rtpClock, jitter, new PriorityQueueScheduler(), null);

    @BeforeClass
    public static void setUpClass() throws Exception {
    }

    @AfterClass
    public static void tearDownClass() throws Exception {
    }

    @Before
    public void setUp() {
        rtpClock.setClockRate(8000);
        jitterBuffer.reset();
    }

    @After
    public void tearDown() {
    }


    @Test
    public void testNoPacketsAfter3Packets() throws Exception {
        RtpPacket[] stream = createStream(100);

        Frame[] media = new Frame[stream.length];
        for (int i = 0; i < stream.length; i++) {
            if (i == 3) {
                // Wait for 3020ms while reading and not writing any packets.
                for (int j = 0; j < 50 * 3; j++) {
                    wallClock.tick(20000000L);
                    media[i] = jitterBuffer.read(wallClock.getTime());
                }
                wallClock.tick(20000000L);
            } else {
                wallClock.tick(20000000L);
            }
            jitterBuffer.write(stream[i], AVProfile.audio.find(8));
            media[i] = jitterBuffer.read(wallClock.getTime());
        }


        for (int i = 0; i < media.length; i++) {
            Frame f = media[i];
            if (i == 0 || i == 1 || i == 4 || i == 5) {
                assertNull("Frames should be missing", f);
            } else {
                assertNotNull("Frames should be present", f);
            }
        }
    }

    @Test
    public void failingOutOforder() throws Exception {
        RtpPacket[] stream = createStream(100);
        HashMap<Integer, LinkedList<RtpPacket>> packets = reorderWithDelay(10, 10, stream);

        Frame[] media = new Frame[stream.length];
        int[] bufferSize = new int[stream.length];
        for (int i = 0; i < stream.length; i++) {
            if (packets.containsKey(i)) {
                for (RtpPacket rtpPacket : packets.get(i)) {
                    System.out.println("Packet: " + rtpPacket.getSeqNumber());
                    jitterBuffer.write(rtpPacket, AVProfile.audio.find(8));
                }
            }

            wallClock.tick(20000000L);
            media[i] = jitterBuffer.read(wallClock.getTime());
            bufferSize[i] = jitterBuffer.getBufferSize();
        }

//        this.checkMaxBufferSize(bufferSize, 9);
        this.checkSequence(media);
        assertEquals(0, 0);
    }

    @Test
    public void testBuffering() throws Exception {
        RtpPacket[] stream = createStream(1000);

        Frame[] media = new Frame[stream.length];
        int[] bufferSize = new int[stream.length];
        for (int i = 0; i < stream.length; i++) {
            wallClock.tick(20000000L);
            if (i%5 == 0) {
                jitterBuffer.write(stream[i], AVProfile.audio.find(8));
                jitterBuffer.write(stream[i+1], AVProfile.audio.find(8));
                jitterBuffer.write(stream[i+2], AVProfile.audio.find(8));
                jitterBuffer.write(stream[i+3], AVProfile.audio.find(8));
                jitterBuffer.write(stream[i+4], AVProfile.audio.find(8));
            }
            media[i] = jitterBuffer.read(wallClock.getTime());
            bufferSize[i] = jitterBuffer.getBufferSize();
        }

        this.checkMaxBufferSize(bufferSize, 6);
        this.checkSequence(media);
        assertEquals(0, 0);
    }

    @Test
    public void testNormalReadWrite() throws Exception {
        RtpPacket[] stream = createStream(1000);

        Frame[] media = new Frame[stream.length];
        for (int i = 0; i < stream.length; i++) {
            wallClock.tick(20000000L);
            jitterBuffer.write(stream[i], AVProfile.audio.find(8));
            media[i] = jitterBuffer.read(wallClock.getTime());
        }

        this.checkSequence(media);
        assertEquals(0, 0);
    }

    @Test
    public void testOrdering() throws Exception {
        RtpPacket[] stream = createStream(1000);
        shuffle(stream);

        for (RtpPacket rtpPacket : stream) {
            jitterBuffer.write(rtpPacket, AVProfile.audio.find(8));
        }

        Frame[] media = new Frame[stream.length];
        for (int i = 0; i < stream.length; i++) {
            wallClock.tick(20000000L);
            media[i] = jitterBuffer.read(wallClock.getTime());
        }

        this.checkSequence(media);
        assertEquals(0, 0);
    }

    private RtpPacket[] createStream(int size) {
        RtpPacket[] stream = new RtpPacket[size];

        int it = 1234500000;
        int it2 = 0;
        int it3 = 1234560000;
        int segment = stream.length/3;
        for (int i = 0; i < segment; i++) {
            stream[i] = RtpPacket.outgoing(local,remote,false, 8, i + 1, 160 * (i+1) + it, 123, new byte[160], 0, 160);
        }

        for (int i = segment; i < 2*segment; i++) {
            stream[i] = RtpPacket.outgoing(local,remote,false, 8, i + 1, 160 * (i+1) + it2, 123, new byte[160], 0, 160);
        }

        for (int i = 2*segment; i < stream.length; i++) {
            stream[i] = RtpPacket.outgoing(local,remote,false, 8, i + 1, 160 * (i+1) + it3, 123, new byte[160], 0, 160);
        }

        return stream;
    }

    private void checkMaxBufferSize(int[] buffer, int maxSize) throws Exception {
        for (int j : buffer) {
            assertTrue("Max buffer size exceeded " + j + " > " + maxSize, j <= maxSize);
        }
    }

    private void checkSequence(Frame[] media) throws Exception {
        int loss = 0;
        boolean res = true;
        for (int i = 0; i < media.length - 1; i++) {
            if (media[i] == null) {
                loss++;
                continue;
            }

            if (media[i + 1] == null) {
                continue;
            }

            res &= (media[i + 1].getSequenceNumber() - media[i].getSequenceNumber() == 1);
        }

        System.out.println("Loss: " + ((100 * loss) / media.length));
        int lossPercent = (100 * loss) / media.length;
        assertTrue("Loss is too high " + lossPercent, lossPercent < 10);
        assertTrue("Wrong sequence ", res);
    }

    private void shuffle(RtpPacket[] stream) {
        Random rnd = new Random();
        for (int k = 0; k < stream.length; k++) {
            int i = rnd.nextInt(stream.length - 1);
            int j = rnd.nextInt(stream.length - 1);

            RtpPacket tmp = stream[i];
            stream[i] = stream[j];
            stream[j] = tmp;
        }
    }

    private HashMap<Integer, LinkedList<RtpPacket>> reorderWithDelay(int delay, int jitter, RtpPacket[] stream) {
        HashMap<Integer, LinkedList<RtpPacket>> result = new HashMap<Integer, LinkedList<RtpPacket>>();

        Random rnd = new Random();

        for (int i = 0; i < stream.length; i++) {
            int key = i + delay;
            if (jitter > 0) {
                if (rnd.nextBoolean()) {
                    key += rnd.nextInt(jitter);
                } else {
                    key -= rnd.nextInt(jitter);
                }

                if (key < 0) key = 0;
            }


            LinkedList<RtpPacket> list;
            if (!result.containsKey(key)) {
                list = new LinkedList<RtpPacket>();
            } else {
                list = result.get(key);
            }

            list.push(stream[i]);
            result.put(key, list);
        }

        return result;
    }

}
