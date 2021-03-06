/*
 * TeleStax, Open Source Cloud Communications
 * Copyright 2011-2015, Telestax Inc and individual contributors
 * by the @authors tag. 
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

package org.mobicents.media.server.impl.srtp;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

import io.netty.util.internal.StringUtil;
import org.apache.logging.log4j.Logger;
import org.bouncycastle.crypto.tls.DTLSServerProtocol;
import org.bouncycastle.crypto.tls.DTLSTransport;
import org.bouncycastle.crypto.tls.DatagramTransport;
import org.mobicents.media.server.impl.rtp.crypto.DtlsSrtpServer;
import org.mobicents.media.server.impl.rtp.crypto.PacketTransformer;
import org.mobicents.media.server.impl.rtp.crypto.SRTPPolicy;
import org.mobicents.media.server.impl.rtp.crypto.SRTPTransformEngine;
import org.mobicents.media.server.io.network.channel.PacketHandler;
import org.mobicents.media.server.io.network.channel.PacketHandlerException;

/**
 * Handler to process DTLS packets.
 * 
 * @author Henrique Rosa (henrique.rosa@telestax.com)
 *
 */
public class DtlsHandler implements PacketHandler, DatagramTransport {

    private static final AtomicLong THREAD_COUNTER = new AtomicLong(0);

    private static final Logger logger = org.apache.logging.log4j.LogManager.getLogger(DtlsHandler.class);

    public static final int DEFAULT_MTU = 1500;

    private final static int MIN_IP_OVERHEAD = 20;
    private final static int MAX_IP_OVERHEAD = MIN_IP_OVERHEAD + 64;
    private final static int UDP_OVERHEAD = 8;
    public final static int MAX_DELAY = 10000;

    // Packet Handler properties
    private int pipelinePriority;

    // Network properties
    private int mtu;
    private final int receiveLimit;
    private final int sendLimit;

    // DTLS Handshake properties
    private DtlsSrtpServer server;
    private DatagramChannel channel;
    private final Queue<ByteBuffer> rxQueue;
    private volatile boolean handshakeComplete;
    private volatile boolean handshakeFailed;
    private volatile boolean handshaking;
    private Thread worker;
    private String localHashFunction;
    private String remoteHashFunction;
    private String remoteFingerprint;
    private String localFingerprint;
    private AtomicLong startTime = new AtomicLong(0);

    private final List<DtlsListener> listeners;

    // SRTP properties
    // http://tools.ietf.org/html/rfc5764#section-4.2
    private PacketTransformer srtpEncoder;
    private PacketTransformer srtpDecoder;
    private PacketTransformer srtcpEncoder;
    private PacketTransformer srtcpDecoder;

    public DtlsHandler() {
        this.pipelinePriority = 0;

        // Network properties
        this.mtu = DEFAULT_MTU;
        this.receiveLimit = Math.max(0, mtu - MIN_IP_OVERHEAD - UDP_OVERHEAD);
        this.sendLimit = Math.max(0, mtu - MAX_IP_OVERHEAD - UDP_OVERHEAD);

        // Handshake properties
        this.server = new DtlsSrtpServer();
        this.rxQueue = new ConcurrentLinkedQueue<>();
        this.handshakeComplete = false;
        this.handshakeFailed = false;
        this.handshaking = false;
        this.localHashFunction = "SHA-256";
        this.remoteHashFunction = "";
        this.remoteFingerprint = "";
        this.localFingerprint = "";

        this.listeners = new ArrayList<DtlsListener>();
    }

    public void setChannel(DatagramChannel channel) {
        this.channel = channel;
    }

    public void addListener(DtlsListener listener) {
        if (!this.listeners.contains(listener)) {
            this.listeners.add(listener);
        }
    }

    public boolean isHandshakeComplete() {
        return handshakeComplete;
    }

    public String getLocalFingerprint() {
        if (this.localFingerprint == null || this.localFingerprint.isEmpty()) {
            this.localFingerprint = this.server.generateFingerprint(this.localHashFunction);
        }
        return localFingerprint;
    }

    public void resetLocalFingerprint() {
        this.localFingerprint = "";
    }

    public void setRemoteFingerprint(String hashFunction, String fingerprint) {
        this.remoteHashFunction = hashFunction;
        this.remoteFingerprint = fingerprint;
    }

    private byte[] getMasterServerKey() {
        return server.getSrtpMasterServerKey();
    }

    private byte[] getMasterServerSalt() {
        return server.getSrtpMasterServerSalt();
    }

    private byte[] getMasterClientKey() {
        return server.getSrtpMasterClientKey();
    }

    private byte[] getMasterClientSalt() {
        return server.getSrtpMasterClientSalt();
    }

    private SRTPPolicy getSrtpPolicy() {
        return server.getSrtpPolicy();
    }

    private SRTPPolicy getSrtcpPolicy() {
        return server.getSrtcpPolicy();
    }

    /**
     * Generates an SRTP encoder for outgoing RTP packets using keying material from the DTLS handshake.
     */
    private PacketTransformer generateRtpEncoder() {
        return new SRTPTransformEngine(getMasterServerKey(), getMasterServerSalt(), getSrtpPolicy(), getSrtcpPolicy())
                .getRTPTransformer();
    }

    /**
     * Generates an SRTP decoder for incoming RTP packets using keying material from the DTLS handshake.
     */
    private PacketTransformer generateRtpDecoder() {
        return new SRTPTransformEngine(getMasterClientKey(), getMasterClientSalt(), getSrtpPolicy(), getSrtcpPolicy())
                .getRTPTransformer();
    }

    /**
     * Generates an SRTCP encoder for outgoing RTCP packets using keying material from the DTLS handshake.
     */
    private PacketTransformer generateRtcpEncoder() {
        return new SRTPTransformEngine(getMasterServerKey(), getMasterServerSalt(), getSrtpPolicy(), getSrtcpPolicy())
                .getRTCPTransformer();
    }

    /**
     * Generates an SRTCP decoder for incoming RTCP packets using keying material from the DTLS handshake.
     */
    private PacketTransformer generateRtcpDecoder() {
        return new SRTPTransformEngine(getMasterClientKey(), getMasterClientSalt(), getSrtpPolicy(), getSrtcpPolicy())
                .getRTCPTransformer();
    }

    /**
     * Decodes an RTP Packet
     * 
     * @param packet The encoded RTP packet
     * @return The decoded RTP packet. Returns null is packet is not valid.
     */
    public byte[] decodeRTP(byte[] packet, int offset, int length, SocketAddress localPeer, SocketAddress remotePeer) {
        return this.srtpDecoder.reverseTransform(packet, offset, length, localPeer, remotePeer);
    }

    /**
     * Encodes an RTP packet
     * 
     * @param packet The decoded RTP packet
     * @return The encoded RTP packet
     */
    public byte[] encodeRTP(byte[] packet, int offset, int length, SocketAddress localPeer, SocketAddress remotePeer) {
        return this.srtpEncoder.transform(packet, offset, length, localPeer, remotePeer);
    }

    /**
     * Decodes an RTCP Packet
     * 
     * @param packet The encoded RTP packet
     * @return The decoded RTP packet. Returns null is packet is not valid.
     */
    public byte[] decodeRTCP(byte[] packet, int offset, int length, SocketAddress localPeer, SocketAddress remotePeer) {
        return this.srtcpDecoder.reverseTransform(packet, offset, length, localPeer, remotePeer);
    }

    /**
     * Encodes an RTCP packet
     * 
     * @param packet The decoded RTP packet
     * @return The encoded RTP packet
     */
    public byte[] encodeRTCP(byte[] packet, int offset, int length, SocketAddress localPeer, SocketAddress remotePeer) {
        return this.srtcpEncoder.transform(packet, offset, length, localPeer, remotePeer);
    }

    public void handshake() {
        try {
            logger.info("Starting DTLS Handshake: " +
                    " local: " + (this.channel != null ? this.channel.getLocalAddress().toString() : "null") +
                    " remote: " + (this.channel != null ? this.channel.getRemoteAddress().toString() : "null") +
                    " localFingerPrint: " + DtlsHandler.this.localFingerprint +
                    " remoteFingerPrint: " + DtlsHandler.this.remoteFingerprint
            );
        } catch (Throwable ex) {}
        if (!handshaking && !handshakeComplete) {
            this.handshaking = true;
            this.startTime.set(System.currentTimeMillis());
            this.worker = new Thread(new HandshakeWorker(), "DTLS-Server-" + THREAD_COUNTER.incrementAndGet());
            this.worker.start();
        }
    }

    private void fireHandshakeComplete() {
        if (this.listeners.size() > 0) {
            Iterator<DtlsListener> iterator = listeners.iterator();
            while (iterator.hasNext()) {
                iterator.next().onDtlsHandshakeComplete();
            }
        }
    }

    private void fireHandshakeFailed(Throwable e) {
        if (this.listeners.size() > 0) {
            Iterator<DtlsListener> iterator = listeners.iterator();
            while (iterator.hasNext()) {
                iterator.next().onDtlsHandshakeFailed(e);
            }
        }
    }

    public void reset() {
        // XXX try not to create the server every time!
        this.server = new DtlsSrtpServer();
        this.channel = null;
        this.srtcpDecoder = null;
        this.srtcpEncoder = null;
        this.srtpDecoder = null;
        this.srtpEncoder = null;
        this.remoteHashFunction = "";
        this.remoteFingerprint = "";
        this.localFingerprint = "";
        this.handshakeComplete = false;
        this.handshakeFailed = false;
        this.handshaking = false;
        this.startTime.set(0);
        this.listeners.clear();
    }

    @Override
    public int compareTo(PacketHandler o) {
        if (o == null) {
            return 1;
        }
        return this.getPipelinePriority() - o.getPipelinePriority();
    }

    @Override
    public boolean canHandle(byte[] packet) {
        return canHandle(packet, packet.length, 0);
    }

    @Override
    public boolean canHandle(byte[] packet, int dataLength, int offset) {
        // https://tools.ietf.org/html/rfc5764#section-5.1.2
        int contentType = packet[offset] & 0xff;
        return (contentType > 19 && contentType < 64);
    }

    public byte[] handle(byte[] packet, InetSocketAddress localPeer, InetSocketAddress remotePeer)
            throws PacketHandlerException {
        return this.handle(packet, packet.length, 0, localPeer, remotePeer);
    }

    @Override
    public byte[] handle(byte[] packet, int dataLength, int offset, InetSocketAddress localPeer, InetSocketAddress remotePeer)
            throws PacketHandlerException {
        this.rxQueue.offer(ByteBuffer.wrap(packet, offset, dataLength));
        return null;
    }

    @Override
    public int getPipelinePriority() {
        return this.pipelinePriority;
    }

    public void setPipelinePriority(int pipelinePriority) {
        this.pipelinePriority = pipelinePriority;
    }

    @Override
    public int getReceiveLimit() throws IOException {
        return this.receiveLimit;
    }

    @Override
    public int getSendLimit() throws IOException {
        return this.sendLimit;
    }

    @Override
    public int receive(byte[] buf, int off, int len, int waitMillis) throws IOException {
        // MEDIA-48: DTLS handshake thread does not terminate
        // https://telestax.atlassian.net/browse/MEDIA-48
        if (this.hasTimeout()) {
            close();
            logger.error("DTLS Handshake took too long " +(System.currentTimeMillis() - this.startTime.get())+" (>" + MAX_DELAY + "ms): " +
                    " local: " + (this.channel != null ? this.channel.getLocalAddress().toString() : "null") +
                    " remote: " + (this.channel != null ? this.channel.getRemoteAddress().toString() : "null") +
                    " localFingerPrint: " + DtlsHandler.this.localFingerprint +
                    " remoteFingerPrint: " + DtlsHandler.this.remoteFingerprint
            );
            throw new IllegalStateException("Handshake is taking too long!:"+(System.currentTimeMillis() - this.startTime.get())+" (>" + MAX_DELAY + "ms): ");
        }

        int attempts = waitMillis;
        do {
            ByteBuffer data = this.rxQueue.poll();
            if (data != null) {
                logger.info("DTLS Handshake packet received delta: " +(System.currentTimeMillis() - this.startTime.get())+"ms " +
                        " attempt: " + attempts +
                        " local: " + (this.channel != null ? this.channel.getLocalAddress().toString() : "null") +
                        " remote: " + (this.channel != null ? this.channel.getRemoteAddress().toString() : "null") +
                        " localFingerPrint: " + DtlsHandler.this.localFingerprint +
                        " remoteFingerPrint: " + DtlsHandler.this.remoteFingerprint
                );
                data.get(buf, off, data.limit());
                return data.limit();
            }

            try {
                Thread.sleep(1);
            } catch (InterruptedException e) {
                logger.warn("Could not sleep thread to receive DTLS data.");
            } finally {
                attempts--;
            }
        } while (attempts > 0);

        // Throw IO exception if no data was received in this interval. Restarts outbound flight.
        throw new SocketTimeoutException("Could not receive DTLS packet in " + waitMillis);
    }

    @Override
    public void send(byte[] buf, int off, int len) throws IOException {
        if (!hasTimeout()) {
            if (this.channel != null && this.channel.isOpen() && this.channel.isConnected()) {
                this.channel.send(ByteBuffer.wrap(buf, off, len), channel.getRemoteAddress());
                //Change Cipher spec has a content type of 20, thus we know that we want to delay the next packet.
                if ((buf[off] & 0xff) == 20) {
                    try {
                        Thread.sleep(20);
                    } catch (InterruptedException e) {
                        logger.warn("Could not sleep thread to wait for sending of DTLS packets");
                    }
                }
            } else {
                logger.warn("Handler skipped send operation because channel is not open or connected.");
            }
        } else {
            logger.warn("Handler has timed out so send operation will be skipped: " +(System.currentTimeMillis() - this.startTime.get())+" (>" + MAX_DELAY + "ms): " +
                    " local: " + (this.channel != null ? this.channel.getLocalAddress().toString() : "null") +
                    " remote: " + (this.channel != null ? this.channel.getRemoteAddress().toString() : "null") +
                    " localFingerPrint: " + DtlsHandler.this.localFingerprint +
                    " remoteFingerPrint: " + DtlsHandler.this.remoteFingerprint
            );
        }
    }

    @Override
    public void close() throws IOException {
        this.rxQueue.clear();
        this.startTime.set(0);
        this.channel = null;
    }

    private boolean hasTimeout() {
        return (System.currentTimeMillis() - this.startTime.get()) > MAX_DELAY;
    }

    private class HandshakeWorker implements Runnable {

        public void run() {
            int sz = DtlsHandler.this.rxQueue.size();
            DtlsHandler.this.rxQueue.clear();
            SecureRandom secureRandom = new SecureRandom();
            DTLSServerProtocol serverProtocol = new DTLSServerProtocol(secureRandom);

            try {
                // Perform the handshake in a non-blocking fashion
                serverProtocol.accept(server, DtlsHandler.this);

                DtlsHandler.logger.info("Handshake completed in : " + (System.currentTimeMillis() - DtlsHandler.this.startTime.get()) + " ms (" +
                        " qSz: " + sz +
                        " local: " + (DtlsHandler.this.channel != null ? DtlsHandler.this.channel.getLocalAddress().toString() : "null") +
                        " remote: " + (DtlsHandler.this.channel != null ? DtlsHandler.this.channel.getRemoteAddress().toString() : "null") +
                        " localFingerPrint: " + DtlsHandler.this.localFingerprint +
                        " remoteFingerPrint: " + DtlsHandler.this.remoteFingerprint +
                        ")"
                );

                // Prepare the shared key to be used in RTP streaming
                server.prepareSrtpSharedSecret();

                logger.info(
                "Handshake got consensus on: " +
                    "startedHandshakeAT: " + startTime.get() +
                    ", local: " + (DtlsHandler.this.channel != null ? DtlsHandler.this.channel.getLocalAddress().toString() : "null") +
                    ", remote: " + (DtlsHandler.this.channel != null ? DtlsHandler.this.channel.getRemoteAddress().toString() : "null") +
                    ", localFingerPrint: " + DtlsHandler.this.localFingerprint +
                    ", remoteFingerPrint: " + DtlsHandler.this.remoteFingerprint +
                    ", serverRandom: " +  StringUtil.toHexString(server.getServerRandom()) +
                    ", clientRandom: " + StringUtil.toHexString(server.getClientRandom()) +
                    ", getMasterClientKey: " + StringUtil.toHexString(getMasterClientKey()) +
                    ", getMasterServerKey: " + StringUtil.toHexString(getMasterServerKey()) +
                    ", getMasterClientSalt: " + StringUtil.toHexString(getMasterClientSalt()) +
                    ", getMasterServerSalt: " + StringUtil.toHexString(getMasterServerSalt()) +
                    ", srtpPolicy: " + getSrtpPolicy().toParsableString() + ", " +
                    ", srtcpPolicy: " + getSrtcpPolicy().toParsableString()
                );

                // Generate encoders for DTLS traffic
                srtpDecoder = generateRtpDecoder();
                srtpEncoder = generateRtpEncoder();
                srtcpDecoder = generateRtcpDecoder();
                srtcpEncoder = generateRtcpEncoder();

                // Declare handshake as complete
                handshakeComplete = true;
                handshakeFailed = false;
                handshaking = false;

                // Warn listeners handshake completed
                fireHandshakeComplete();
            } catch (Exception e) {
                try {
                    logger.error("DTLS handshake failed. Reason:" + e.getMessage() + ": " +
                                    " qSz: " + sz +
                                    " local: " + (DtlsHandler.this.channel != null ? DtlsHandler.this.channel.getLocalAddress().toString() : "null") +
                                    " remote: " + (DtlsHandler.this.channel != null ? DtlsHandler.this.channel.getRemoteAddress().toString() : "null") +
                                    " localFingerPrint: " + DtlsHandler.this.localFingerprint +
                                    " remoteFingerPrint: " + DtlsHandler.this.remoteFingerprint
                            , e);
                } catch (Exception ex) {}
                // Declare handshake as failed
                handshakeComplete = false;
                handshakeFailed = true;
                handshaking = false;

                // Warn listeners handshake completed
                fireHandshakeFailed(e);
            }
        }

    }

}
