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

/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */

package org.mobicents.media.server.impl.rtp;

import static org.junit.Assert.assertEquals;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.util.ArrayList;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mobicents.media.server.component.Dsp;
import org.mobicents.media.server.component.DspFactoryImpl;
import org.mobicents.media.server.component.audio.AudioComponent;
import org.mobicents.media.server.component.audio.AudioMixer;
import org.mobicents.media.server.component.oob.OOBComponent;
import org.mobicents.media.server.component.oob.OOBMixer;
import org.mobicents.media.server.impl.resource.dtmf.DetectorImpl;
import org.mobicents.media.server.impl.rtp.statistics.RtpStatistics;
import org.mobicents.media.server.io.network.BindType;
import org.mobicents.media.server.io.network.UdpManager;
import org.mobicents.media.server.io.sdp.format.AVProfile;
import org.mobicents.media.server.scheduler.Clock;
import org.mobicents.media.server.scheduler.Scheduler;
import org.mobicents.media.server.scheduler.ServiceScheduler;
import org.mobicents.media.server.scheduler.WallClock;
import org.mobicents.media.server.scheduler.PriorityQueueScheduler;
import org.mobicents.media.server.spi.ConnectionMode;
import org.mobicents.media.server.spi.dtmf.DtmfDetectorListener;
import org.mobicents.media.server.spi.dtmf.DtmfEvent;
import org.mobicents.media.server.spi.format.AudioFormat;
import org.mobicents.media.server.spi.format.FormatFactory;
import org.mobicents.media.server.spi.format.Formats;

/**
 *
 * @author oifa yulian
 */
public class RTPEventTest implements DtmfDetectorListener {

    //clock and scheduler
    private Clock clock;
    private RtpClock rtpClock;
    private PriorityQueueScheduler mediaScheduler;
    private Scheduler scheduler;

    private ChannelsManager channelsManager;
    private UdpManager udpManager;

//    private SpectraAnalyzer analyzer;
    private DetectorImpl detector;
    
    private RtpChannel channel;
    
    private DspFactoryImpl dspFactory = new DspFactoryImpl();
    
    private Dsp dsp11;

    private Sender sender;
    
    private AudioMixer audioMixer;
    private OOBMixer oobMixer;
    
    private AudioComponent detectorComponent;
    private OOBComponent oobComponent;

    private InetSocketAddress local = new InetSocketAddress(7777);
    private InetSocketAddress remote = new InetSocketAddress(7778);
    
    private int count=0;
    
    public RTPEventTest() {
        scheduler = new ServiceScheduler();
    }

    @Before
    public void setUp() throws Exception {
        AudioFormat pcma = FormatFactory.createAudioFormat("pcma", 8000, 8, 1);
        Formats fmts = new Formats();
        fmts.add(pcma);
        
        Formats dstFormats = new Formats();
        dstFormats.add(FormatFactory.createAudioFormat("LINEAR", 8000, 16, 1));
        
        dspFactory.addCodec("org.mobicents.media.server.impl.dsp.audio.g711.alaw.Encoder");
        dspFactory.addCodec("org.mobicents.media.server.impl.dsp.audio.g711.alaw.Decoder");

        dsp11 = dspFactory.newProcessor();

        //use default clock
        clock = new WallClock();
        rtpClock = new RtpClock(clock);

        //create single thread scheduler
        mediaScheduler = new PriorityQueueScheduler();
        mediaScheduler.setClock(clock);
        mediaScheduler.start();

        udpManager = new UdpManager(scheduler);
        scheduler.start();
        udpManager.start();
        
        channelsManager = new ChannelsManager(udpManager);
        channelsManager.setScheduler(mediaScheduler);
        
        detector = new DetectorImpl("dtmf", mediaScheduler);
        detector.setVolume(-35);
        detector.setDuration(40);
        detector.addListener(this);
        
        channel = channelsManager.getRtpChannel(new RtpStatistics(rtpClock), rtpClock, rtpClock, null);
        channel.bind(BindType.Default, false);

        sender = new Sender(channel.getLocalPort());
        
        channel.setRemotePeer(new InetSocketAddress("127.0.0.1", 9200));
        channel.setInputDsp(dsp11);
        channel.setFormatMap(AVProfile.audio);

        audioMixer=new AudioMixer(mediaScheduler);
        audioMixer.addComponent(channel.getAudioComponent());
        
        detectorComponent=new AudioComponent(1);
        detectorComponent.addOutput(detector.getAudioOutput());
        detectorComponent.updateMode(true,true);
        audioMixer.addComponent(detectorComponent);               
        
        oobMixer=new OOBMixer(mediaScheduler);
        oobMixer.addComponent(channel.getOobComponent());
        
        oobComponent=new OOBComponent(1);
        oobComponent.addOutput(detector.getOOBOutput());
        oobComponent.updateMode(true,true);
        oobMixer.addComponent(oobComponent);
    }

    @After
    public void tearDown() {
    	channel.close();
    	audioMixer.stop();
    	oobMixer.stop();
        udpManager.stop();
        mediaScheduler.stop();
        scheduler.stop();
        sender.close();
    }

    @Test
    public void testTransmission() throws Exception {
    	channel.updateMode(ConnectionMode.SEND_RECV);
    	audioMixer.start();
    	oobMixer.start();
    	detector.activate();
    	
        new Thread(sender).start();

        Thread.sleep(5000);
        
        channel.updateMode(ConnectionMode.INACTIVE);
    	audioMixer.stop();
    	oobMixer.stop();
    	detector.deactivate();
    	
    	assertEquals(4,count);
    }

    public void process(DtmfEvent event) {
    	count++;
        System.out.println("TONE=" + event.getTone());
    }
    
    private class Sender implements Runnable {
        
        private DatagramSocket socket;
        private ArrayList<byte[]> stream = new ArrayList<byte[]>();
        
        private InetSocketAddress dst;

        private byte[][] evt1 = new byte[][]{
            new byte[] {0x0b, 0x0a, 0x00, (byte)0xa0},
            new byte[] {0x0b, 0x0a, 0x01, (byte)0x40},
            new byte[] {0x0b, 0x0a, 0x01, (byte)0xe0},
            new byte[] {0x0b, 0x0a, 0x02, (byte)0x80},
            new byte[] {0x0b, 0x0a, 0x03, (byte)0x20},
            new byte[] {0x0b, 0x0a, 0x03, (byte)0xc0},
            new byte[] {0x0b, 0x0a, 0x04, (byte)0x60},
            new byte[] {0x0b, 0x0a, 0x05, (byte)0x00},
            new byte[] {0x0b, (byte)0x8a, 0x05, (byte)0xa0},
            new byte[] {0x0b, (byte)0x8a, 0x05, (byte)0xa0},
            new byte[] {0x0b, (byte)0x8a, 0x05, (byte)0xa0}
        };
        
        //lets make empty content for alaw
        private byte[] zeroContent=new byte[160];
        
        public Sender(int port) throws SocketException {
        	for(int i=0;i<zeroContent.length;i++)
        		zeroContent[i]=(byte)0xD5;
        	
            dst = new InetSocketAddress("127.0.0.1", port);
            socket = new DatagramSocket(new InetSocketAddress("127.0.0.1", 9200));
            this.generateSequence(250);
        }
        
        private void generateSequence(int len) {
            boolean event = false;
            boolean marker = false;
            int t=0;
            int count = 0;

            int it = 12345;
            for (int i = 0; i < len; i++) {
                // todo fix;
                RtpPacket p = null ; // new RtpPacket(172, false);

                if (i % 50 == 0 && i > 0) {
                   event = true;
                   marker = true;
                   t = 160 * (i + 1);
                   count = 0;
                }

                if (event) {
                    p = RtpPacket.outgoing(local,remote,marker, 101, i + 1, t + it, 123, evt1[count++], 0, 4);
                    //p.wrap(marker, 101, i + 1, t + it, 123, evt1[count++], 0, 4);
                    marker = false;
                    if (count == evt1.length) {
                        event = false;
                    }
                } else {
                    p = RtpPacket.outgoing(local,remote,marker, 8, i + 1, 160 * (i + 1) + it, 123, zeroContent, 0, 160);
                   // p.wrap(marker, 8, i + 1, 160 * (i + 1) + it, 123, zeroContent, 0, 160);
                }
                // todo: find much more elegant method than this copy
//                byte[] data = new byte[p.getBuffer().limit()];
//                p.getBuffer().get(data);
                stream.add(p.toArray());
            }
        }
        
        @Override
        public void run() {
            while (!stream.isEmpty()) {
                byte[] data = stream.remove(0);
                try {
                    DatagramPacket p = new DatagramPacket(data, 0, data.length, dst);
                    socket.send(p);
                    
                    Thread.sleep(20);
                } catch (Exception e) {
                }
            }
        }
        
        public void close() {
            if (socket != null) {
                socket.close();
            }
        }
    }        
}