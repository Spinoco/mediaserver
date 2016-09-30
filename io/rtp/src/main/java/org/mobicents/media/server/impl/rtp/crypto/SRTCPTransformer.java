/**
 * 
 * Code derived and adapted from the Jitsi client side SRTP framework.
 * 
 * Distributed under LGPL license.
 * See terms of license at gnu.org.
 */
package org.mobicents.media.server.impl.rtp.crypto;

import java.net.SocketAddress;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * SRTCPTransformer implements PacketTransformer.
 * It encapsulate the encryption / decryption logic for SRTCP packets
 * 
 * @author Bing SU (nova.su@gmail.com)
 * @author Werner Dittmann &lt;Werner.Dittmann@t-online.de>
 */
public class SRTCPTransformer implements PacketTransformer {

	
    private SRTPTransformEngine forwardEngine;
    private SRTPTransformEngine reverseEngine;

    /** All the known SSRC's corresponding SRTCPCryptoContexts */
    private ConcurrentMap<Long,SRTCPCryptoContext> contexts;

    /**
     * Constructs a SRTCPTransformer object.
     * 
     * @param engine The associated SRTPTransformEngine object for both
     *            transform directions.
     */
    public SRTCPTransformer(SRTPTransformEngine engine)
    {
        this(engine, engine);
    }

    /**
     * Constructs a SRTCPTransformer object.
     * 
     * @param forwardEngine The associated SRTPTransformEngine object for
     *            forward transformations.
     * @param reverseEngine The associated SRTPTransformEngine object for
     *            reverse transformations.
     */
    public SRTCPTransformer(SRTPTransformEngine forwardEngine, SRTPTransformEngine reverseEngine) {
        this.forwardEngine = forwardEngine;
        this.reverseEngine = reverseEngine;
        this.contexts = new ConcurrentHashMap<>();
    }

    /**
     * Encrypts a SRTCP packet
     * 
     * @param pkt plain SRTCP packet to be encrypted
     * @return encrypted SRTCP packet
     */
    public byte[] transform(byte[] pkt, SocketAddress localPeer, SocketAddress remotePeer) {
    	return transform(pkt, 0, pkt.length, localPeer, remotePeer);
    }
    
    public byte[] transform(byte[] pkt, int offset, int length, SocketAddress localPeer, SocketAddress remotePeer) {
    	// Wrap the data into raw packet for readable format
        RawPacket decrypted = new RawPacket(pkt,offset,length ,localPeer, remotePeer);
    	
    	// Associate the packet with its encryption context
        long ssrc = decrypted.getRTCPSSRC();
        SRTCPCryptoContext context = contexts.get(ssrc);

        if (context == null) {
            context = forwardEngine.getDefaultContextControl().deriveContext(ssrc);
            context.deriveSrtcpKeys();
            SRTCPCryptoContext current = contexts.putIfAbsent(ssrc, context);
            if (current != null) context = current;
        }
        
        // Secure packet into SRTCP format
        RawPacket encrypted = context.transformPacket(decrypted);
        return encrypted.getData();
    }

    public byte[] reverseTransform(byte[] pkt, SocketAddress localPeer, SocketAddress remotePeer) {
    	return reverseTransform(pkt, 0, pkt.length, localPeer, remotePeer);
    }
    
    public byte[] reverseTransform(byte[] pkt, int offset, int length, SocketAddress localPeer, SocketAddress remotePeer) {
    	// wrap data into raw packet for readable format

        RawPacket encrypted = new RawPacket(pkt,offset,length,localPeer,remotePeer);

    	// Associate the packet with its encryption context
        long ssrc = encrypted.getRTCPSSRC();
        SRTCPCryptoContext context = this.contexts.get(ssrc);

        if (context == null) {
            context = reverseEngine.getDefaultContextControl().deriveContext(ssrc);
            context.deriveSrtcpKeys();
            SRTCPCryptoContext current = contexts.putIfAbsent(ssrc, context);
            if (current != null) context = current;
        }
        
        // Decode packet to RTCP format
        RawPacket decrypted = context.reverseTransformPacket(encrypted);
        if(decrypted != null) return decrypted.getData();
        else return null;
    }

    /**
     * Close the transformer and underlying transform engine.
     * 
     * The close functions closes all stored crypto contexts. This deletes key data 
     * and forces a cleanup of the crypto contexts.
     */
    public void close() 
    {
        forwardEngine.close();
        if (forwardEngine != reverseEngine)
            reverseEngine.close();

        Iterator<Map.Entry<Long, SRTCPCryptoContext>> iter
            = contexts.entrySet().iterator();

        while (iter.hasNext()) 
        {
            Map.Entry<Long, SRTCPCryptoContext> entry = iter.next();
            SRTCPCryptoContext context = entry.getValue();

            iter.remove();
            if (context != null)
                context.close();
        }
    }
}
