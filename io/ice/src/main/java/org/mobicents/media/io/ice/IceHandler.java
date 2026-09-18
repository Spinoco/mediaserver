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

package org.mobicents.media.io.ice;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.logging.log4j.Logger;
import org.mobicents.media.io.ice.events.IceEventListener;
import org.mobicents.media.io.ice.events.SelectedCandidatesEvent;
import org.mobicents.media.io.stun.StunException;
import org.mobicents.media.io.stun.messages.StunMessage;
import org.mobicents.media.io.stun.messages.StunMessageFactory;
import org.mobicents.media.io.stun.messages.StunRequest;
import org.mobicents.media.io.stun.messages.StunResponse;
import org.mobicents.media.io.stun.messages.attributes.StunAttribute;
import org.mobicents.media.io.stun.messages.attributes.StunAttributeFactory;
import org.mobicents.media.io.stun.messages.attributes.general.MessageIntegrityAttribute;
import org.mobicents.media.io.stun.messages.attributes.general.UsernameAttribute;
import org.mobicents.media.server.io.network.TransportAddress;
import org.mobicents.media.server.io.network.TransportAddress.TransportProtocol;
import org.mobicents.media.server.io.network.channel.PacketHandler;
import org.mobicents.media.server.io.network.channel.PacketHandlerException;

/**
 * @author Henrique Rosa (henrique.rosa@telestax.com)
 *
 */
public class IceHandler implements PacketHandler {
    
    private static final Logger logger = org.apache.logging.log4j.LogManager.getLogger(IceHandler.class);

    // Packet Handler properties
    private int pipelinePriority;

    // Candidate properties
    private final short componentId;

    // Message Integrity
    private IceAuthenticator authenticator;

    // Handshake state
    private final IceEventListener iceListener;
    private final AtomicBoolean candidateSelected;
    // Address of the peer whose nominated (USE-CANDIDATE) check we last accepted.
    // Used to detect a mid-session change of remote address (NAT rebinding,
    // re-nomination, mobility) and to gate switching on MESSAGE-INTEGRITY.
    private volatile InetSocketAddress selectedRemotePeer;

    public IceHandler(short componentId, IceEventListener iceListener) {
        // Packet Handler properties
        this.pipelinePriority = 1;

        // Candidate properties
        switch (componentId) {
            case IceComponent.RTP_ID:
            case IceComponent.RTCP_ID:
                this.componentId = componentId;
                break;

            default:
                throw new IllegalArgumentException("Invalid component ID: " + componentId);
        }

        // Handshake state
        this.iceListener = iceListener;
        this.candidateSelected = new AtomicBoolean(false);
    }

    public short getComponentId() {
        return componentId;
    }
    
    public void setAuthenticator(IceAuthenticator authenticator) {
        this.authenticator = authenticator;
    }

    @Override
    public int compareTo(PacketHandler o) {
        return (o == null) ? 1 : this.getPipelinePriority() - o.getPipelinePriority();
    }

    /*
     * All STUN messages MUST start with a 20-byte header followed by zero or more Attributes.
     * The STUN header contains a STUN message type, magic cookie, transaction ID, and message length.
     * 
     *  0                   1                   2                   3
     *  0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
     * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
     * |0 0|     STUN Message Type     |         Message Length        |
     * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
     * |                         Magic Cookie                          |
     * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
     * |                                                               |
     * |                     Transaction ID (96 bits)                  |
     * |                                                               |
     * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
     * 
     * @see <a href="http://tools.ietf.org/html/rfc5389#page-10">RFC5389</a>
     */
    @Override
    public boolean canHandle(byte[] packet) {
        return canHandle(packet, packet.length, 0);
    }

    @Override
    public boolean canHandle(byte[] packet, int dataLength, int offset) {
        byte b0 = packet[offset];
        int b0Int = b0 & 0xff;

        // STUN message must start with 20-byte header followed by zero or more attributes
        // First byte must be less than 2 (https://tools.ietf.org/html/rfc5764#section-5.1.2)
        if (b0Int < 2 && dataLength >= 20) {
            // The most significant 2 bits of every STUN message MUST be zeroes.
            boolean firstBitsValid = ((b0 & 0xC0) == 0);

            // The magic cookie field MUST contain the fixed value 0x2112A442 in network byte order.
            boolean hasMagicCookie = packet[offset + 4] == StunMessage.MAGIC_COOKIE[0]
                    && packet[offset + 5] == StunMessage.MAGIC_COOKIE[1] && packet[offset + 6] == StunMessage.MAGIC_COOKIE[2]
                    && packet[offset + 7] == StunMessage.MAGIC_COOKIE[3];
            return firstBitsValid && hasMagicCookie;
        }
        return false;
    }

    public byte[] handle(byte[] packet, InetSocketAddress localPeer, InetSocketAddress remotePeer)
            throws PacketHandlerException {
        return handle(packet, packet.length, 0, localPeer, remotePeer);
    }

    @Override
    public byte[] handle(byte[] packet, int dataLength, int offset, InetSocketAddress localPeer, InetSocketAddress remotePeer)
            throws PacketHandlerException {
        try {
            StunMessage message = StunMessage.decode(packet, (char) offset, (char) dataLength);
            if (message instanceof StunRequest) {
                return processRequest((StunRequest) message, localPeer, remotePeer);
            } else if (message instanceof StunResponse) {
                return processResponse((StunResponse) message);
            } else {
                // TODO STUN Indication is not supported as of yet
                return null;
            }
        } catch (StunException e) {
            throw new PacketHandlerException("Could not decode STUN packet", e);
        } catch (IOException e) {
            throw new PacketHandlerException(e.getMessage(), e);
        }
    }

    private byte[] processRequest(StunRequest request, InetSocketAddress localPeer, InetSocketAddress remotePeer)
            throws IOException {
        // The agent MUST use a short-term credential to authenticate the request and perform a message integrity check.
        UsernameAttribute remoteUnameAttribute = (UsernameAttribute) request.getAttribute(StunAttribute.USERNAME);
        String remoteUsername = new String(remoteUnameAttribute.getUsername());

        // The agent MUST consider the username to be valid if it consists of two values separated by a colon, where the first
        // value is equal to the username fragment generated by the agent in an offer or answer for a session in-progress.
        if (!this.authenticator.validateUsername(remoteUsername)) {
            // TODO return error response
            throw new IOException("Invalid username " + remoteUsername);
        }

        // The username for the credential is formed by concatenating the username fragment provided by the peer with the
        // username fragment of the agent sending the request, separated by a colon (":").
        int colon = remoteUsername.indexOf(":");
        String localUFrag = remoteUsername.substring(0, colon);
        String remoteUfrag = remoteUsername.substring(colon + 1);

        // Produce Binding Response
        TransportAddress transportAddress = new TransportAddress(remotePeer.getAddress(), remotePeer.getPort(),
                TransportProtocol.UDP);
        StunResponse response = StunMessageFactory.createBindingResponse(request, transportAddress);
        byte[] transactionID = request.getTransactionId();
        try {
            response.setTransactionID(transactionID);
        } catch (StunException e) {
            throw new IOException("Illegal STUN Transaction ID: " + new String(transactionID), e);
        }

        // Add USERNAME and MESSAGE-INTEGRITY attribute in the response.
        // The responses utilize the same usernames and passwords as the requests.
        String localUsername = remoteUfrag.concat(":").concat(localUFrag);
        StunAttribute unameAttribute = StunAttributeFactory.createUsernameAttribute(localUsername);
        response.addAttribute(unameAttribute);

        byte[] localKey = this.authenticator.getLocalKey(localUFrag);

        // Authenticate the request: every ICE connectivity/consent check MUST carry a
        // MESSAGE-INTEGRITY that is a valid HMAC-SHA1 over the message keyed by our
        // local ICE password (RFC 5389 s.10.1.2 / RFC 8445). A request that fails this
        // did not come from the peer we negotiated with over signalling, so drop it.
        // (The RFC allows replying 401; dropping keeps the responder simple and reveals
        // nothing to an attacker.)
        if (!request.isMessageIntegrityValid(localKey)) {
            logger.warn("Dropping STUN request from " + remotePeer + " with missing/invalid MESSAGE-INTEGRITY.");
            throw new IOException("Invalid message integrity " + remoteUsername);
        }

        MessageIntegrityAttribute integrityAttribute = StunAttributeFactory.createMessageIntegrityAttribute(remoteUsername, localKey);
        response.addAttribute(integrityAttribute);

        // If the client issues a USE-CANDIDATE, select the candidate. The request is
        // already authenticated (unauthenticated ones were dropped above), so this is
        // applied uniformly to the first nomination and to any later change of remote
        // address (NAT rebinding, re-nomination, mobility).
        if (request.containsAttribute(StunAttribute.USE_CANDIDATE)) {
            boolean firstSelection = (this.selectedRemotePeer == null);
            boolean addressChanged = !firstSelection && !remotePeer.equals(this.selectedRemotePeer);
            if (firstSelection || addressChanged) {
                if (addressChanged) {
                    logger.info("Remote peer moved from " + this.selectedRemotePeer + " to " + remotePeer
                            + "; following nominated address.");
                } else if (logger.isDebugEnabled()) {
                    logger.debug("Selected candidate remote: " + remotePeer + ", local: " + localPeer);
                }
                this.selectedRemotePeer = remotePeer;
                this.candidateSelected.set(true);
                this.iceListener.onSelectedCandidates(new SelectedCandidatesEvent(remotePeer));
            }
        }

        // Pass response to the server
        return response.encode();
    }

    private byte[] processResponse(StunResponse response) {
        throw new UnsupportedOperationException("Support to handle STUN responses is not implemented.");
    }

    @Override
    public int getPipelinePriority() {
        return this.pipelinePriority;
    }

    public void setPipelinePriority(int pipelinePriority) {
        this.pipelinePriority = pipelinePriority;
    }
    
    public void reset() {
        this.authenticator = null;
        this.candidateSelected.set(false);
        this.selectedRemotePeer = null;
    }

}
