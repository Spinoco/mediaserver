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

package org.mobicents.media.io.stun.messages;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import org.junit.Test;
import org.mobicents.media.io.stun.messages.attributes.StunAttribute;

/**
 * Tests {@link StunRequest#isMessageIntegrityValid(byte[])} against the
 * "Sample Request" test vector from RFC 5769 section 2.1: a real STUN Binding
 * Request, signed with a short-term credential, given as a byte-exact hex
 * dump. (Section 2.2 of that RFC is a sample *response*, which decodes into a
 * {@link StunResponse} rather than a {@link StunRequest}, so it cannot
 * exercise this method - the request in section 2.1 is the matching vector.)
 *
 * @see <a href="https://www.rfc-editor.org/rfc/rfc5769.html#section-2.1">RFC 5769 section 2.1</a>
 */
public class StunRequestTest {

	private static final String HEADER = "000100582112a442b7e7a701bc34d686fa87dfae";
	private static final String SOFTWARE = "802200105354554e207465737420636c69656e74"; // "STUN test client"
	private static final String PRIORITY = "002400046e0001ff";
	private static final String ICE_CONTROLLED = "80290008932ff9b151263b36";
	private static final String USERNAME = "000600096576746a3a68367659202020"; // "evtj:h6vY" + padding
	private static final String MESSAGE_INTEGRITY = "000800149aeaa70cbfd8cb56781ef2b5b2d3f249c1b571a2";
	private static final String FINGERPRINT = "80280004e57a3bcf";

	// RFC 5769 section 2.1 "Sample Request", byte-exact.
	private static final String SAMPLE_REQUEST_HEX =
			HEADER + SOFTWARE + PRIORITY + ICE_CONTROLLED + USERNAME + MESSAGE_INTEGRITY + FINGERPRINT;

	// The same message with MESSAGE-INTEGRITY and FINGERPRINT removed, and the
	// header's length field patched down accordingly (0x58 -> 0x38).
	private static final String SAMPLE_REQUEST_NO_INTEGRITY_HEX =
			"000100382112a442b7e7a701bc34d686fa87dfae" + SOFTWARE + PRIORITY + ICE_CONTROLLED + USERNAME;

	private static final byte[] PASSWORD = "VOkJxbRl1RmTxUk/WvJxBt".getBytes(StandardCharsets.UTF_8);

	@Test
	public void testValidMessageIntegrityIsAccepted() throws Exception {
		StunRequest request = decodeRequest(SAMPLE_REQUEST_HEX);
		assertTrue(request.isMessageIntegrityValid(PASSWORD));
	}

	@Test
	public void testWrongKeyIsRejected() throws Exception {
		StunRequest request = decodeRequest(SAMPLE_REQUEST_HEX);
		byte[] wrongKey = "not-the-password".getBytes(StandardCharsets.UTF_8);
		assertFalse(request.isMessageIntegrityValid(wrongKey));
	}

	@Test
	public void testTamperedAttributeIsRejected() throws Exception {
		byte[] raw = fromHex(SAMPLE_REQUEST_HEX);
		// Flip a bit inside the PRIORITY attribute's value (byte 44, "6e"): it is
		// covered by MESSAGE-INTEGRITY but its position/length are unaffected.
		raw[44] ^= 0x01;
		// Drop the trailing FINGERPRINT attribute (8 bytes) and patch the header's
		// length field accordingly (0x58 -> 0x50), so decode() - which validates
		// FINGERPRINT's own CRC on the way in - doesn't reject the tampered
		// message before isMessageIntegrityValid gets a chance to.
		byte[] withoutFingerprint = Arrays.copyOf(raw, raw.length - 8);
		withoutFingerprint[2] = 0x00;
		withoutFingerprint[3] = 0x50;
		StunRequest request = decodeRequest(withoutFingerprint);
		assertFalse(request.isMessageIntegrityValid(PASSWORD));
	}

	@Test
	public void testMissingMessageIntegrityIsRejected() throws Exception {
		StunRequest request = decodeRequest(SAMPLE_REQUEST_NO_INTEGRITY_HEX);
		assertNull(request.getAttribute(StunAttribute.MESSAGE_INTEGRITY));
		assertFalse(request.isMessageIntegrityValid(PASSWORD));
	}

	private static StunRequest decodeRequest(String hex) throws Exception {
		return decodeRequest(fromHex(hex));
	}

	private static StunRequest decodeRequest(byte[] raw) throws Exception {
		StunMessage message = StunMessage.decode(raw, (char) 0, (char) raw.length);
		return (StunRequest) message;
	}

	private static byte[] fromHex(String hex) {
		byte[] result = new byte[hex.length() / 2];
		for (int i = 0; i < result.length; i++) {
			result[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
		}
		return result;
	}
}
