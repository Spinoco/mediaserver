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

package org.mobicents.media.server.io.network;

import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.util.concurrent.TimeUnit;

import org.apache.logging.log4j.Logger;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mobicents.media.server.io.network.channel.Channel;
import org.mobicents.media.server.scheduler.Scheduler;
import org.mobicents.media.server.scheduler.ServiceScheduler;

/**
 *
 * @author yulian oifa
 */
public class UdpPeripheryTest {
    
    private static final Logger LOGGER = org.apache.logging.log4j.LogManager.getLogger(UdpPeripheryTest.class);

    private UdpManager udpPeriphery;
    private Scheduler scheduler = new ServiceScheduler(); 

    @Before
    public void setUp() throws IOException {
        udpPeriphery = new UdpManager(scheduler);
        scheduler.start();
        udpPeriphery.start();
    }

    @After
    public void tearDown() {
        udpPeriphery.stop();
        scheduler.stop();
        try {
            scheduler.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            LOGGER.warn("Scheduler stopped because thread was interrupted.");
        }
    }

    /**
     * Test of open method, of class UdpPeriphery.
     */
    @Test
    public void testOpen() throws Exception {
    	SelectionKey channel = udpPeriphery.open(new TestHandler());
        udpPeriphery.bind((DatagramChannel) channel.channel(), 1024, BindType.Local);
        assertTrue("Excepted bound socket", ((DatagramChannel)channel.channel()).socket().isBound());
    }

    /**
     * Test of poll method, of class UdpPeriphery.
     */
    @Test
    public void testPoll() throws IOException {
        long s = System.nanoTime();
        InetSocketAddress address = new InetSocketAddress("127.0.0.1", 9201);
        DatagramChannel channel = DatagramChannel.open();
        channel.configureBlocking(false);
        channel.socket().bind(address);
        long duration = System.nanoTime() - s;
        System.out.println("dur=" + duration);
        channel.socket().close();
    }

    private class TestHandler implements Channel {

        @Override
        public String getLocalHost() {
            return null;
        }

        @Override
        public int getLocalPort() {
            return 0;
        }

        @Override
        public SocketAddress getLocalAddress() {
            return null;
        }

        @Override
        public void receive() throws IOException {

        }

        @Override
        public void send() throws IOException {

        }

        @Override
        public boolean hasPendingData() {
            return false;
        }

        @Override
        public boolean isConnected() {
            return false;
        }

        @Override
        public boolean isOpen() {
            return false;
        }

        @Override
        public void bind(SocketAddress address) throws IOException {

        }

        @Override
        public void connect(SocketAddress address) throws IOException {

        }

        @Override
        public void disconnect() throws IOException {

        }

        @Override
        public void open() throws IOException {

        }

        @Override
        public void close() {

        }
    }

}