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

package org.mobicents.media.server.mgcp;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.log4j.Logger;
import org.mobicents.media.server.io.network.ProtocolHandler;
import org.mobicents.media.server.io.network.UdpManager;
import org.mobicents.media.server.mgcp.message.MgcpMessage;
import org.mobicents.media.server.mgcp.message.MgcpRequest;
import org.mobicents.media.server.mgcp.message.MgcpResponse;
import org.mobicents.media.server.spi.listener.Listeners;
import org.mobicents.media.server.spi.listener.TooManyListenersException;

/**
 *
 * @author Oifa Yulian
 */
public class MgcpProvider {

    private String name;

    // event listeners
    private Listeners<MgcpListener> listeners = new Listeners<MgcpListener>();

    // Underlying network interface
    private final UdpManager transport;

    // datagram channel
    private DatagramChannel channel;

    // MGCP port number
    private final int port;

    // transmission buffer
    private final ConcurrentLinkedQueue<ByteBuffer> txBuffer = new ConcurrentLinkedQueue<ByteBuffer>();

    // receiver buffer
    private final ByteBuffer rxBuffer = ByteBuffer.allocate(8192);

    // pool of events
    private final ConcurrentLinkedQueue<MgcpEventImpl> events = new ConcurrentLinkedQueue<MgcpEventImpl>();

    private final static Logger logger = Logger.getLogger(MgcpProvider.class);

    /**
     * Creates new provider instance.
     * 
     * @param transport the UDP interface instance.
     * @param port port number to bind
     */
    public MgcpProvider(UdpManager transport, int port) {
        this("", transport, port);
    }

    /**
     * Creates new provider instance. Used for tests
     * 
     * @param transport the UDP interface instance.
     * @param port port number to bind
     */
    protected MgcpProvider(String name, UdpManager transport, int port) {
        this.name = name;
        this.transport = transport;
        this.port = port;

        // prepare event pool
        for (int i = 0; i < 100; i++) {
            events.offer(new MgcpEventImpl(this));
        }

        for (int i = 0; i < 100; i++) {
            txBuffer.offer(ByteBuffer.allocate(8192));
        }
    }

    /**
     * Creates new event object.
     * 
     * @param eventID the event identifier: REQUEST or RESPONSE
     * @return event object.
     */
    public MgcpEvent createEvent(int eventID, SocketAddress address) {

        MgcpEventImpl evt = events.poll();
        if (evt == null)
            evt = new MgcpEventImpl(this);

        evt.inQueue.set(false);
        evt.setEventID(eventID);
        evt.setAddress(address);
        return evt;
    }

    /**
     * Sends message.
     * 
     * @param message the message to send.
     * @param destination the IP address of the destination.
     */
    public void send(MgcpEvent event, SocketAddress destination) throws IOException {
        MgcpMessage msg = event.getMessage();
        ByteBuffer currBuffer = txBuffer.poll();
        if (currBuffer == null)
            currBuffer = ByteBuffer.allocate(8192);

        msg.write(currBuffer);
        channel.send(currBuffer, destination);

        currBuffer.clear();
        txBuffer.offer(currBuffer);
    }

    /**
     * Sends message.
     * 
     * @param message the message to send.
     */
    public void send(MgcpEvent event) throws IOException {
        MgcpMessage msg = event.getMessage();
        ByteBuffer currBuffer = txBuffer.poll();
        if (currBuffer == null)
            currBuffer = ByteBuffer.allocate(8192);

        msg.write(currBuffer);
        channel.send(currBuffer, event.getAddress());

        currBuffer.clear();
        txBuffer.offer(currBuffer);
    }

    /**
     * Sends message.
     * 
     * @param message the message to send.
     * @param destination the IP address of the destination.
     */
    public void send(MgcpMessage message, SocketAddress destination) throws IOException {
        ByteBuffer currBuffer = txBuffer.poll();
        if (currBuffer == null)
            currBuffer = ByteBuffer.allocate(8192);

        message.write(currBuffer);
        channel.send(currBuffer, destination);

        currBuffer.clear();
        txBuffer.offer(currBuffer);
    }

    /**
     * Registers new even listener.
     * 
     * @param listener the listener instance to be registered.
     * @throws TooManyListenersException
     */
    public void addListener(MgcpListener listener) throws TooManyListenersException {
        listeners.add(listener);
    }

    /**
     * Unregisters event listener.
     * 
     * @param listener the event listener instance to be unregistered.
     */
    public void removeListener(MgcpListener listener) {
        listeners.remove(listener);
    }

    public void activate() {
        try {
            logger.info("Opening channel");
            channel = transport.open(new MGCPHandler());
        } catch (IOException e) {
            logger.info("Could not open UDP channel: " + e.getMessage());
            return;
        }

        try {
            logger.info("Binding channel to " + transport.getLocalBindAddress() + ":" + port);
            transport.bindLocal(channel, port);
        } catch (IOException e) {
            try {
                channel.close();
            } catch (IOException ex) {
                logger.warn("Could not close MGCP Provider channel", e);
            }
            logger.info("Could not open UDP channel: " + e.getMessage());
            return;
        }
    }

    public void shutdown() {
        if (channel != null) {
            try {
                channel.close();
            } catch (IOException e) {
                logger.error("Could not shutdown MGCP Provider", e);
            }
        }
    }

    private void recycleEvent(MgcpEventImpl event) {
        if (event.inQueue.getAndSet(true))
            logger.warn("====================== ALARM ALARM ALARM==============");
        else {
            event.response.clean();
            event.request.clean();
            events.offer(event);
        }
    }

    /**
     * MGCP message handler asynchronous implementation.
     * 
     */
    private class MGCPHandler implements ProtocolHandler {

        // mgcp message receiver.
        private Receiver receiver = new Receiver();

        @Override
        public void receive(DatagramChannel channel) {
            receiver.perform();
        }

        @Override
        public void send(DatagramChannel channel) {
        }

        @Override
        public boolean isReadable() {
            return false;
        }

        @Override
        public boolean isWriteable() {
            return false;
        }

        @Override
        public void setKey(SelectionKey key) {
        }

        @Override
        public void onClosed() {
            // try to reopen mgcp port
            shutdown();
            activate();
        }
    }

    /**
     * Receiver of the MGCP packets.
     */
    private class Receiver {
        private SocketAddress address;

        public Receiver() {
            super();
        }

        public long perform() {
            rxBuffer.clear();
            try {
                if ((address = channel.receive(rxBuffer)) != null) {
                    rxBuffer.flip();

                    if (logger.isDebugEnabled()) {
                        logger.debug("Receive  message " + rxBuffer.limit() + " bytes length");
                    }

                    if (rxBuffer.limit() == 0) {
//                        continue;
                        return 0L;
                    }

                    byte b = rxBuffer.get();
                    rxBuffer.rewind();

                    // update event ID.
                    int msgType = -1;
                    if (b >= 48 && b <= 57) {
                        msgType = MgcpEvent.RESPONSE;
                    } else {
                        msgType = MgcpEvent.REQUEST;
                    }

                    MgcpEvent evt = createEvent(msgType, address);

                    // parse message
                    if (logger.isDebugEnabled()) {
                        final byte[] data = rxBuffer.array();
                        logger.debug("Parsing message: " + new String(data, 0, rxBuffer.limit()));
                    }
                    MgcpMessage msg = evt.getMessage();
                    msg.read(rxBuffer);

                    // deliver event to listeners
                    if (logger.isDebugEnabled()) {
                        logger.debug("Dispatching message");
                    }
                    listeners.dispatch(evt);

                    // clean buffer for next reading
                    rxBuffer.clear();
                }
            } catch (Exception e) {
                logger.error("Could not process message", e);
            }
            return 0;
        }
    }

    /**
     * MGCP event object implementation.
     * 
     */
    private class MgcpEventImpl implements MgcpEvent {

        // provides instance
        private MgcpProvider provider;

        // event type
        private int eventID;

        // patterns for messages: request and response
        private MgcpRequest request = new MgcpRequest();
        private MgcpResponse response = new MgcpResponse();

        // the source address
        private SocketAddress address;

        private AtomicBoolean inQueue = new AtomicBoolean(true);

        /**
         * Creates new event object.
         * 
         * @param provider the MGCP provider instance.
         */
        public MgcpEventImpl(MgcpProvider provider) {
            this.provider = provider;
        }

        @Override
        public MgcpProvider getSource() {
            return provider;
        }

        @Override
        public MgcpMessage getMessage() {
            return eventID == MgcpEvent.REQUEST ? request : response;
        }

        @Override
        public int getEventID() {
            return eventID;
        }

        /**
         * Modifies event type to this event objects.
         * 
         * @param eventID the event type constant.
         */
        protected void setEventID(int eventID) {
            this.eventID = eventID;
        }

        @Override
        public void recycle() {
            recycleEvent(this);
        }

        @Override
        public SocketAddress getAddress() {
            return address;
        }

        /**
         * Modify source address of the message.
         * 
         * @param address the socket address as an object.
         */
        protected void setAddress(SocketAddress address) {
            InetSocketAddress a = (InetSocketAddress) address;
            this.address = new InetSocketAddress(a.getHostName(), a.getPort());
        }
    }
}
