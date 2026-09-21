/*
 * 
 * Code derived and adapted from the Jitsi client side STUN framework.
 * 
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */

package org.mobicents.media.io.stun.messages;

import java.security.MessageDigest;
import java.util.Arrays;

import org.apache.logging.log4j.Logger;
import org.mobicents.media.io.stun.messages.attributes.StunAttribute;
import org.mobicents.media.io.stun.messages.attributes.StunAttributeFactory;
import org.mobicents.media.io.stun.messages.attributes.general.MessageIntegrityAttribute;

/**
 * Represents a STUN Request message.
 */
public class StunRequest extends StunMessage {

	private static final Logger logger = org.apache.logging.log4j.LogManager.getLogger(StunRequest.class);

	public StunRequest() {
		super();
	}

	@Override
	public void setMessageType(char requestType)
			throws IllegalArgumentException {
		if (!isRequestType(requestType)) {
			throw new IllegalArgumentException((int) (requestType)
					+ " - is not a valid request type.");
		}
		super.setMessageType(requestType);
	}



	/**
	 * Verifies the MESSAGE-INTEGRITY of this request against the given key (the
	 * local ICE password for the addressed ufrag). The HMAC-SHA1 covers the STUN
	 * header and every attribute preceding MESSAGE-INTEGRITY, with the
	 * message-length field patched to stop right after MESSAGE-INTEGRITY
	 * (RFC 8489 section 14.5). Returns false on any parsing problem rather than
	 * throwing, so a verification failure can never break packet handling.
	 *
	 * @param key the local key (ICE password) that MESSAGE-INTEGRITY should be verified against
	 */
	public boolean isMessageIntegrityValid(byte[] key) {
		try {
			byte[] data = this.rawData;
			MessageIntegrityAttribute mi = (MessageIntegrityAttribute) getAttribute(StunAttribute.MESSAGE_INTEGRITY);

			if (data == null || key == null || mi == null) {
				return false;
			} else {

				// Get the position of the header, we only take the bytes of the message up to this point
				// for the integrity check
				int miOffset = mi.getLocationInMessage();

				// defensive copy for when we update the length.
				byte[] buf = new byte[miOffset];
				System.arraycopy(data, this.rawOffset, buf, 0, miOffset);


				// Length of STUN message is only counted after the "StunMessage.HEADER_LENGTH"
				// Then we pretend the Message integrity is present as an attribute.
				int patchedLength = (miOffset - StunMessage.HEADER_LENGTH) + StunAttribute.HEADER_LENGTH + MessageIntegrityAttribute.DATA_LENGTH;
				buf[2] = (byte) ((patchedLength >> 8) & 0xff);
				buf[3] = (byte) (patchedLength & 0xff);

				byte[] expected = MessageIntegrityAttribute.calculateHmacSha1(buf, 0, buf.length, key);
				byte[] received = mi.getHmacSha1Content();

				// Timing-safe comparison: MessageDigest.isEqual is specified to run in
				// constant time for equal-length inputs, which is what we want when
				// comparing a computed HMAC against an attacker-supplied one.
				return expected != null && MessageDigest.isEqual(expected, received);
			}
		} catch (Exception e) {
			logger.warn("Could not verify STUN MESSAGE-INTEGRITY: " + e.getMessage());
			return false;
		}
	}
}
