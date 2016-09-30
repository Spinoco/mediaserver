/**
 * Code derived and adapted from the Jitsi client side SRTP framework.
 * 
 * Distributed under LGPL license.
 * See terms of license at gnu.org.
 */
package org.mobicents.media.server.impl.rtp.crypto;

import javax.xml.bind.DatatypeConverter;
import java.net.SocketAddress;
import java.nio.ByteBuffer;


/**
 * When using TransformConnector, a RTP/RTCP packet is represented using
 * RawPacket. RawPacket stores the buffer holding the RTP/RTCP packet, as well
 * as the inner offset and length of RTP/RTCP packet data.
 *
 * After transformation, data is also store in RawPacket objects, either the
 * original RawPacket (in place transformation), or a newly created RawPacket.
 *
 * Besides packet info storage, RawPacket also provides some other operations
 * such as readInt() to ease the development process.
 *
 * @author Werner Dittmann (Werner.Dittmann@t-online.de)
 * @author Bing SU (nova.su@gmail.com)
 * @author Emil Ivov
 * @author Damian Minkov
 * @author Boris Grozev
 * @author Lyubomir Marinov
 * @author Henrique Rosa (henrique.rosa@telestax.com)
 */
public class RawPacket {
 /**
     * The size of the extension header as defined by RFC 3550.
     */
    public static final int EXT_HEADER_SIZE = 4;

    /**
     * The size of the fixed part of the RTP header as defined by RFC 3550.
     */
    public static final int FIXED_HEADER_SIZE = 12;

    /**
     * Byte array storing the content of this Packet. Note this is never mutated, position is always at 0
     */
     private ByteBuffer buffer;

    private SocketAddress localPeer;
    private SocketAddress remotePeer;


    /** RawPacket(data,0,data.length) **/
    public RawPacket(byte[] data, SocketAddress localPeer, SocketAddress remotePeer) {
        this.buffer =  ByteBuffer.wrap(data);
        this.localPeer = localPeer;
        this.remotePeer = remotePeer;
    }
    /**
     * Initializes a new <tt>RawPacket</tt> instance with a specific
     * <tt>byte</tt> array buffer.
     *
     * @param data the <tt>byte</tt> array to be the buffer of the new
     * instance 
     * @param offset the offset in <tt>buffer</tt> at which the actual data to
     * be represented by the new instance starts
     * @param length the number of <tt>byte</tt>s in <tt>buffer</tt> which
     * constitute the actual data to be represented by the new instance
     */
    public RawPacket(byte[] data, int offset, int length, SocketAddress localPeer, SocketAddress remotePeer) {
    	this.buffer = ByteBuffer.wrap(data,offset,length);
        this.localPeer = localPeer;
        this.remotePeer = remotePeer;
    }

    public RawPacket(ByteBuffer buff, SocketAddress localPeer, SocketAddress remotePeer) {
        this.buffer =  buff.duplicate();
        this.buffer.rewind();
        this.localPeer = localPeer;
        this.remotePeer = remotePeer;
    }
    


    /** gets the copy of this packet data **/
    public byte[] getData() {
    	ByteBuffer buff = this.buffer.duplicate();
        buff.rewind();
    	byte[] data = new byte[buff.limit()];
    	buff.get(data, 0, data.length);
    	return data;
    }

    /** copies content of this buffer from `from` to dest, starting at `offset` of length `len` **/
    public void get(int from, byte[] dest, int offset, int len) {
        ByteBuffer buff = this.buffer.duplicate();
        buff.rewind();
        buff.position(from);
        buff.get(dest,offset,len);
    }

    /**
     * Append a byte array to the end of the packet. This may change the data
     * buffer of this packet.
     *
     * Return copy of this packet with new, fresh array backing it.
     *
     * @param data byte array to append
     * @param len the number of bytes to append
     */
    public RawPacket append(byte[] data, int len) {
        if (data == null || len <= 0 || len > data.length)  {
            throw new IllegalArgumentException("Invalid combination of parameters data and length to append()");
        }

        ByteBuffer buff = ByteBuffer.allocate(buffer.limit()+len);
        buff.put(getData());
        buff.put(data,0,len);
        buff.rewind();

        return new RawPacket(buff, localPeer, remotePeer);
    }


    /**
     * Returns <tt>true</tt> if the extension bit of this packet has been set
     * and <tt>false</tt> otherwise.
     *
     * @return  <tt>true</tt> if the extension bit of this packet has been set
     * and <tt>false</tt> otherwise.
     */
    public boolean getExtensionBit() {
        return (buffer.get(0) & 0x10) == 0x10;
    }

    /**
     * Returns the length of the extensions currently added to this packet.
     *
     * @return the length of the extensions currently added to this packet.
     */
    public int getExtensionLength() {
		int length = 0;
		if (getExtensionBit()) {
			// the extension length comes after the RTP header, the CSRC list,
			// and after two bytes in the extension header called "defined by profile"
			int extLenIndex = FIXED_HEADER_SIZE + getCsrcCount() * 4 + 2;
			length = ((buffer.get(extLenIndex) << 8) | buffer.get(extLenIndex + 1) * 4);
		}
		return length;

    }

    /**
     * Returns the number of CSRC identifiers currently included in this packet.
     *
     * @return the CSRC count for this <tt>RawPacket</tt>.
     */
    public int getCsrcCount() {
        return (this.buffer.get(0) & 0x0f);
    }

    /**
     * Get RTP header length from a RTP packet
     *
     * @return RTP header length from source RTP packet
     */
    public int getHeaderLength() {
    	int length = FIXED_HEADER_SIZE + 4 * getCsrcCount();
        if(getExtensionBit()) {
           length += EXT_HEADER_SIZE + getExtensionLength();
        }
        return length;
    }

    /**
     * Get the length of this packet's data
     *
     * @return length of this packet's data
     */
    public int getLength() {
        return this.buffer.limit();
    }

    /**
     * Get RTP padding size from a RTP packet
     *
     * @return RTP padding size from source RTP packet
     */
    public int getPaddingSize() {
        if ((this.buffer.get(0) & 0x20) == 0) {
            return 0;
        }
        return this.buffer.get(this.buffer.limit());
    }



    /**
     * Get RTP payload length from a RTP packet
     *
     * @return RTP payload length from source RTP packet
     */
    public int getPayloadLength() {
        return getLength() - getHeaderLength();
    }

    /**
     * Get RTP payload type from a RTP packet
     *
     * @return RTP payload type of source RTP packet
     */
    public byte getPayloadType() {
        return (byte) (this.buffer.get(1) & (byte)0x7F);
    }

    /**
     * Get RTCP SSRC from a RTCP packet
     *
     * @return RTP SSRC from source RTP packet
     */
    public int getRTCPSSRC() {
        return readInt(4);
    }

    /**
     * Get RTP sequence number from a RTP packet
     *
     * @return RTP sequence num from source packet
     */
    public int getSequenceNumber()
    {
        return readUnsignedShortAsInt(2);
    }

    /**
     * Get SRTCP sequence number from a SRTCP packet
     *
     * @param authTagLen authentication tag length
     * @return SRTCP sequence num from source packet
     */
    public int getSRTCPIndex(int authTagLen) {
        int offset = getLength() - (4 + authTagLen);
        return readInt(offset);
    }

    /**
     * Get RTP SSRC from a RTP packet
     *
     * @return RTP SSRC from source RTP packet
     */
    public int getSSRC() {
        return readInt(8);
    }

    /**
     * Returns the timestamp for this RTP <tt>RawPacket</tt>.
     *
     * @return the timestamp for this RTP <tt>RawPacket</tt>.
     */
    public long getTimestamp() {
        return readInt(4);
    }



    /**
     * Read a integer from this packet at specified offset
     *
     * @param off start offset of the integer to be read
     * @return the integer to be read
     */
	public int readInt(int off) {
		return ((buffer.get(off) & 0xFF) << 24)
				| ((buffer.get(off+1) & 0xFF) << 16)
				| ((buffer.get(off+2) & 0xFF) << 8)
				| (buffer.get(off+3) & 0xFF);
	}


	
    /**
     * Read an unsigned short at specified offset as a int
     *
     * @param off start offset of the unsigned short
     * @return the int value of the unsigned short at offset
     */
    public int readUnsignedShortAsInt(int off) {
    	this.buffer.position(off);
        int b1 = (0x000000FF & (this.buffer.get()));
        int b2 = (0x000000FF & (this.buffer.get()));
        int val = b1 << 8 | b2;
        return val;
    }
    
    /**
     * Read an unsigned integer as long at specified offset
     *
     * @param off start offset of this unsigned integer
     * @return unsigned integer as long at offset
     */
    public long readUnsignedIntAsLong(int off) {
    	buffer.position(off);
        return (((long)(buffer.get() & 0xff) << 24) |
                ((long)(buffer.get() & 0xff) << 16) |
                ((long)(buffer.get() & 0xff) << 8) |
                ((long)(buffer.get() & 0xff))) & 0xFFFFFFFFL;
    }

    /**
     * Shrink the buffer of this packet by specified length
     *
     * Returns duplicate of this packet with defensive copy of underlying byte array.
     *
     * @param len length to shrink, must be >= 0 and
     */
    public RawPacket shrink(int len) {
        if (len <= 0) return new RawPacket(this.getData(),0,this.getLength(), localPeer, remotePeer);
        else {
            int newLimit = this.getLength() - len;
            if (newLimit < 0) newLimit = 0;
            return new RawPacket(this.getData(),0,newLimit, localPeer, remotePeer);
        }
    }

    public RawPacket withData(byte[] data) {
        return new RawPacket(data,localPeer,remotePeer);
    }


    @Override
    public String toString() {
        return "RawPacket[" +
                "extensionBit=" + getExtensionBit() +
                ", extensionLength=" + getExtensionLength() +
                ", csrcCount=" + getCsrcCount() +
                ", headerLength=" + getHeaderLength() +
                ", length=" + getLength() +
                ", localPeer=" + this.localPeer +
                ", remotePeer=" + this.remotePeer +
                ", data=" + DatatypeConverter.printHexBinary(getData()) +
        ']';
    }
}