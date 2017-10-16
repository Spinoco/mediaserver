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

import org.apache.logging.log4j.Logger;
import org.mobicents.media.server.impl.srtp.DtlsHandler;
import org.mobicents.media.server.io.sdp.format.RTPFormat;
import org.mobicents.media.server.spi.memory.Frame;
import org.mobicents.media.server.spi.memory.Memory;

import java.io.IOException;
import java.io.Serializable;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;

/**
 * A data packet consisting of the fixed RTP header, a possibly empty list of
 * contributing sources, and the payload data. Some underlying protocols may
 * require an encapsulation of the RTP packet to be defined. Typically one
 * packet of the underlying protocol contains a single RTP packet,
 * but several RTP packets may be contained if permitted by the encapsulation
 * method
 *
 * @author Oleg Kulikov
 * @author amit bhayani
 * @author ivelin.ivanov@telestax.com
 */
public class RtpPacket implements Serializable {

    private static final Logger logger = org.apache.logging.log4j.LogManager.getLogger(RtpPacket.class);

    public static final int RTP_PACKET_MAX_SIZE = 8192;

    /**
	 * 
	 */
	private static final long serialVersionUID = -1590053946635208723L;

	/**
     * The size of the fixed part of the RTP header as defined by RFC 3550.
     */
    public static final int FIXED_HEADER_SIZE = 12;
    
    /**
     * The size of the extension header as defined by RFC 3550.
     */
    public static final int EXT_HEADER_SIZE = 4;    
    
    /**
     * Current supported RTP version
     */
    public static final int VERSION = 2;
    
    // underlying byte buffer
    // always kept with position = 0
    private ByteBuffer buffer;

    private SocketAddress localPeer;
    private SocketAddress remotePeer;


    /** construct this rtp packet from data ecrypted by supplied DtlsHandler **/
    public static RtpPacket fromDtlsEncrypted(DtlsHandler handler, SocketAddress localPeer, SocketAddress remotePeer, byte[] packet, int offset, int dataLength) {
        byte[] decoded = handler.decodeRTP(packet,offset,dataLength, localPeer, remotePeer);
        return fromRaw(localPeer,remotePeer,decoded,0,decoded.length);
    }

    /** construct packet from raw, unencrypted data **/
    public static RtpPacket fromRaw(SocketAddress localPeer, SocketAddress remotePeer, byte[] packet, int offset, int dataLength) {
        return new RtpPacket(ByteBuffer.wrap(packet,offset,dataLength), localPeer,remotePeer);
    }

    /**
     * Consturct pakcet to be further sent with RTP
     *
     * @param mark mark field
     * @param payloadType payload type field.
     * @param seqNumber sequence number field
     * @param timestamp timestamp field
     * @param ssrc synchronization source field
     * @param data data buffer
     * @param offset offset in the data buffer
     * @param len the number of bytes
     */
    public static RtpPacket outgoing(SocketAddress localPeer, SocketAddress remotePeer, boolean mark, int payloadType, int seqNumber, long timestamp, long ssrc, byte[] data, int offset, int len) {
        ByteBuffer buffer = ByteBuffer.allocate(RtpPacket.FIXED_HEADER_SIZE + len);

        //no extensions, paddings and cc
        buffer.put((byte)0x80);

        byte b = (byte) (payloadType);
        if (mark) {
            b = (byte) (b | 0x80);
        }

        buffer.put(b);

        //sequence number
        buffer.put((byte) ((seqNumber & 0xFF00) >> 8));
        buffer.put((byte) (seqNumber & 0x00FF));

        //timestamp
        buffer.put((byte) ((timestamp & 0xFF000000) >> 24));
        buffer.put((byte) ((timestamp & 0x00FF0000) >> 16));
        buffer.put((byte) ((timestamp & 0x0000FF00) >> 8));
        buffer.put((byte) ((timestamp & 0x000000FF)));

        //ssrc
        buffer.put((byte) ((ssrc & 0xFF000000) >> 24));
        buffer.put((byte) ((ssrc & 0x00FF0000) >> 16));
        buffer.put((byte) ((ssrc & 0x0000FF00) >> 8));
        buffer.put((byte) ((ssrc & 0x000000FF)));

        buffer.put(data, offset, len);
        buffer.rewind();

        return new RtpPacket(buffer, localPeer, remotePeer);
    }


    RtpPacket(ByteBuffer buffer,  SocketAddress localPeer, SocketAddress remotePeer) {
        this.buffer = buffer.asReadOnlyBuffer();
        this.localPeer = localPeer;
        this.remotePeer = remotePeer;
    }

    /**
     * Verion field.
     *
     * This field identifies the version of RTP. The version defined by
     * this specification is two (2). (The value 1 is used by the first
     * draft version of RTP and the value 0 is used by the protocol
     * initially implemented in the "vat" audio tool.)
     *
     * @return the version value.
     */
    public int getVersion() {
        return (buffer.get(0) & 0xC0) >> 6;
    }

    /**
     * Countributing source field.
     *
     * The CSRC list identifies the contributing sources for the
     * payload contained in this packet. The number of identifiers is
     * given by the CC field. If there are more than 15 contributing
     * sources, only 15 may be identified. CSRC identifiers are inserted by
     * mixers, using the SSRC identifiers of contributing
     * sources. For example, for audio packets the SSRC identifiers of
     * all sources that were mixed together to create a packet are
     * listed, allowing correct talker indication at the receiver.
     *
     * @return synchronization source.
     */
    public int getContributingSource() {
        return buffer.get(0) & 0x0F;
    }

    /**
     * Padding indicator.
     *
     * If the padding bit is set, the packet contains one or more
     * additional padding octets at the end which are not part of the
     * payload. The last octet of the padding contains a count of how
     * many padding octets should be ignored. Padding may be needed by
     * some encryption algorithms with fixed block sizes or for
     * carrying several RTP packets in a lower-layer protocol data
     * unit.
     *
     * @return true if padding bit set.
     */
    public boolean hasPadding() {
        return (buffer.get(0) & 0x20) == 0x020;
    }

    /**
     * Extension indicator.
     *
     * If the extension bit is set, the fixed header is followed by
     * exactly one header extension.
     *
     * @return true if extension bit set.
     */
    public boolean hasExtensions() {
        return (buffer.get(0) & 0x10) == 0x010;
    }

    /**
     * Marker bit.
     *
     * The interpretation of the marker is defined by a profile. It is
     * intended to allow significant events such as frame boundaries to
     * be marked in the packet stream. A profile may define additional
     * marker bits or specify that there is no marker bit by changing
     * the number of bits in the payload type field
     *
     * @return true if marker set.
     */
    public boolean getMarker() {
        return (buffer.get(1) & 0xff & 0x80) == 0x80;
    }

    /**
     * Payload type.
     *
     * This field identifies the format of the RTP payload and
     * determines its interpretation by the application. A profile
     * specifies a default static mapping of payload type codes to
     * payload formats. Additional payload type codes may be defined
     * dynamically through non-RTP means
     *
     * @return integer value of payload type.
     */
    public int getPayloadType() {
        return (buffer.get(1) & 0xff & 0x7f);
    }

    /**
     * Sequence number field.
     *
     * The sequence number increments by one for each RTP data packet
     * sent, and may be used by the receiver to detect packet loss and
     * to restore packet sequence. The initial value of the sequence
     * number is random (unpredictable) to make known-plaintext attacks
     * on encryption more difficult, even if the source itself does not
     * encrypt, because the packets may flow through a translator that
     * does.
     *
     * @return the sequence number value.
     */
    public int getSeqNumber() {
        return buffer.getShort(2) & 0xFFFF;
    }

    /**
     * Timestamp field.
     *
     * The timestamp reflects the sampling instant of the first octet
     * in the RTP data packet. The sampling instant must be derived
     * from a clock that increments monotonically and linearly in time
     * to allow synchronization and jitter calculations.
     * The resolution of the clock must be sufficient for the
     * desired synchronization accuracy and for measuring packet
     * arrival jitter (one tick per video frame is typically not
     * sufficient).  The clock frequency is dependent on the format of
     * data carried as payload and is specified statically in the
     * profile or payload format specification that defines the format,
     * or may be specified dynamically for payload formats defined
     * through non-RTP means. If RTP packets are generated
     * periodically, the nominal sampling instant as determined from
     * the sampling clock is to be used, not a reading of the system
     * clock. As an example, for fixed-rate audio the timestamp clock
     * would likely increment by one for each sampling period.  If an
     * audio application reads blocks covering 160 sampling periods
     * from the input device, the timestamp would be increased by 160
     * for each such block, regardless of whether the block is
     * transmitted in a packet or dropped as silent.
     *
     * The initial value of the timestamp is random, as for the sequence
     * number. Several consecutive RTP packets may have equal timestamps if
     * they are (logically) generated at once, e.g., belong to the same
     * video frame. Consecutive RTP packets may contain timestamps that are
     * not monotonic if the data is not transmitted in the order it was
     * sampled, as in the case of MPEG interpolated video frames. (The
     * sequence numbers of the packets as transmitted will still be
     * monotonic.)
     *
     * @return timestamp value
     */
    public long getTimestamp() {
        return ((long)(buffer.get(4) & 0xff) << 24) |
               ((long)(buffer.get(5) & 0xff) << 16) |
               ((long)(buffer.get(6) & 0xff) << 8)  |
               ((long)(buffer.get(7) & 0xff));
    }

    /**
     * Synchronization source field.
     *
     * The SSRC field identifies the synchronization source. This
     * identifier is chosen randomly, with the intent that no two
     * synchronization sources within the same RTP session will have
     * the same SSRC identifier. Although the
     * probability of multiple sources choosing the same identifier is
     * low, all RTP implementations must be prepared to detect and
     * resolve collisions.  Section 8 describes the probability of
     * collision along with a mechanism for resolving collisions and
     * detecting RTP-level forwarding loops based on the uniqueness of
     * the SSRC identifier. If a source changes its source transport
     * address, it must also choose a new SSRC identifier to avoid
     * being interpreted as a looped source.
     * 
     * @return the sysncronization source 
     */
    public long getSyncSource() {
        return readUnsignedIntAsLong(8);
    }

    /**
     * Get RTCP SSRC from a RTCP packet
     *
     * @return RTP SSRC from source RTP packet
     */
    public long GetRTCPSyncSource()
    {
        return (readUnsignedIntAsLong(4));
    }    
    
    /**
     * Read an unsigned integer as long at specified offset
     *
     * @param off start offset of this unsigned integer
     * @return unsigned integer as long at offset
     */
    public long readUnsignedIntAsLong(int off)
    {
        return (((long)(buffer.get(off) & 0xff) << 24) |
                ((long)(buffer.get(off+1) & 0xff) << 16) |
                ((long)(buffer.get(off+2) & 0xff) << 8) |
                ((long)(buffer.get(off+3) & 0xff))) & 0xFFFFFFFFL;
    }
    
    /**
     * Reads the data transported by RTP in a packet, for example
     * audio samples or compressed video data.
     *
     * @param buff the buffer used for reading
     * @param offset the initial offset inside buffer.
     */
    public void getPayload(byte[] buff, int offset) {
        ByteBuffer tmp = buffer.duplicate(); // defensive copy
        tmp.position(FIXED_HEADER_SIZE);
        try {
            tmp.duplicate().get(buff, offset, buffer.limit() - FIXED_HEADER_SIZE);
        } catch (Exception ex) {
            logger.error("Failed to access payload (rethrow): "+ ex.getMessage() + " offset: " + offset + ",  size: " + (buffer.limit() - FIXED_HEADER_SIZE) + " position" + buffer.position() , ex);
            throw ex;
        }

    }



    @Override
    public String toString() {
        return "RTP Packet[marker=" + getMarker() + ", seq=" + getSeqNumber() +
                ", timestamp=" + getTimestamp() + ", payload_size=" + getPayloadLength() +
                ", payload=" + getPayloadType() +
                ", local=" + this.localPeer +
                ", remote=" + this.remotePeer +
                "]";
    }
    

    /**
     * Get RTP header length from a RTP packet
     *
     * @return RTP header length from source RTP packet
     */
    public int getHeaderLength()
    {
        if(getExtensionBit())
            return FIXED_HEADER_SIZE + 4 * getCsrcCount()
                + EXT_HEADER_SIZE + getExtensionLength();
        else
            return FIXED_HEADER_SIZE + 4 * getCsrcCount();
    }

    /**
     * Get RTP payload length from a RTP packet
     *
     * @return RTP payload length from source RTP packet
     */
    public int getPayloadLength()
    {
        return buffer.limit() - getHeaderLength();
    }
    

    /**
     * Returns the length of the extensions currently added to this packet.
     *
     * @return the length of the extensions currently added to this packet.
     */
    public int getExtensionLength()
    {
        if (!getExtensionBit())
            return 0;

        //the extension length comes after the RTP header, the CSRC list, and
        //after two bytes in the extension header called "defined by profile"
        int extLenIndex =  FIXED_HEADER_SIZE
                        + getCsrcCount()*4 + 2;
        
        return ((buffer.get(extLenIndex) << 8) | buffer.get(extLenIndex + 1) * 4);
    }

    /**
     * Returns <tt>true</tt> if the extension bit of this packet has been set
     * and false otherwise.
     *
     * @return  <tt>true</tt> if the extension bit of this packet has been set
     * and false otherwise.
     */
    public boolean getExtensionBit()
    {
        return (buffer.get(0) & 0x10) == 0x10;
    }    

    /**
     * Returns the number of CSRC identifiers currently included in this packet.
     *
     * @return the CSRC count for this <tt>RawPacket</tt>.
     */
    public int getCsrcCount()
    {
        return (buffer.get(0) & 0x0f);
    }
    

    

    /**
     * Get the length of this packet's raw data
     *
     * @return length of this packet's raw data
     */
    public int getLength()
    {
        return buffer.limit();
    }
    
    /**
     * Function that wil encode with dtls this packet and returns bytebuffer of this packet that can be used
     * to send packet to the network.
     * @param dtlsHandler
     * @return null, if decoding of rtp data failed
     */
    public ByteBuffer dtlsEncodeToSend(DtlsHandler dtlsHandler) {
        byte[] rtpData = toArray();
        byte[] srtpData = dtlsHandler.encodeRTP(rtpData, 0, rtpData.length, localPeer, remotePeer);
        if(srtpData == null || srtpData.length == 0) {
            logger.warn("Could not secure RTP packet! Packet dropped: " + this.toString());
            return null;
        } else {
            return ByteBuffer.wrap(srtpData);
        }
    }

    /**
     * Buidls a frame from this packet.
     * @return
     */
    public Frame toFrame(RtpClock clock, RTPFormat format) {
        Frame f = Memory.allocate(getPayloadLength());
        // put packet into buffer irrespective of its sequence number
        f.setHeader(null);
        f.setSequenceNumber(getSeqNumber());
        // here time is in milliseconds
        f.setTimestamp(clock.convertToAbsoluteTime(getTimestamp()));
        f.setOffset(0);
        f.setLength(getPayloadLength());
        getPayload(f.getData(), 0);

        // set format
        f.setFormat(format.getFormat());

        return f;
    }

    /** sends content of this packet to channel **/
    public int sendTo(DatagramChannel channel) throws IOException {
        return channel.send(buffer, remotePeer);
    }

    public SocketAddress getLocalPeer() {
        return localPeer;
    }

    public SocketAddress getRemotePeer() {
        return remotePeer;
    }

    /** copy content of this packet payload to new array of bytes **/
    public byte[] payloadToArray() {
        byte[] array = new byte[buffer.limit() - RtpPacket.FIXED_HEADER_SIZE];
        getPayload(array,0);
        return array;
    }

    /** copy content of this packet (header + payload) to new array of bytes **/
    public byte[] toArray() {
        byte[] array = new byte[buffer.limit()];
        ByteBuffer tmp = buffer.duplicate(); // defensive copy
        tmp.get(array);
        return array;
    }
}
