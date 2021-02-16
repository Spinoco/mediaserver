/*
 * TeleStax, Open Source Cloud Communications
 * Copyright 2011-2014, Telestax Inc and individual contributors
 * by the @authors tag.
 *
 * This program is free software: you can redistribute it and/or modify
 * under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation; either version 3 of
 * the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>
 *
 */

package org.mobicents.media.server.impl.rtp;

import java.io.IOException;
import java.net.PortUnreachableException;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;

import org.apache.logging.log4j.Logger;
import org.mobicents.media.server.impl.rtp.rfc2833.DtmfOutput;
import org.mobicents.media.server.impl.rtp.statistics.RtpStatistics;
import org.mobicents.media.server.impl.srtp.DtlsHandler;
import org.mobicents.media.server.io.sdp.format.AVProfile;
import org.mobicents.media.server.io.sdp.format.RTPFormat;
import org.mobicents.media.server.io.sdp.format.RTPFormats;
import org.mobicents.media.server.scheduler.PriorityQueueScheduler;
import org.mobicents.media.server.spi.memory.Frame;

/**
 * Transmits RTP packets over a channel.
 * 
 * @author Oifa Yulian
 * @author Henrique Rosa (henrique.rosa@telestax.com)
 *
 */
public class RtpTransmitter {
	
	private static final Logger LOGGER = org.apache.logging.log4j.LogManager.getLogger(RtpTransmitter.class);
	
	// Channel properties
	private DatagramChannel channel;
	private final RtpClock rtpClock;
	private final RtpStatistics statistics;
	private boolean dtmfSupported;
	private final RTPOutput rtpOutput;
	private final DtmfOutput dtmfOutput;

	// Packet representations with internal buffers

	// WebRTC
	private DtlsHandler dtlsHandler;
	private boolean secure;

	// Details of a transmitted packet
	private RTPFormats formats;
	private RTPFormat currentFormat;
	private long timestamp;
	private long dtmfTimestamp;
	private int sequenceNumber;
	private int dtmfSequenceNumber;

	public RtpTransmitter(final PriorityQueueScheduler scheduler, final RtpClock clock, final RtpStatistics statistics) {
		this.rtpClock = clock;
		this.statistics = statistics;
		this.dtmfSupported = false;
		this.rtpOutput = new RTPOutput(scheduler, this);
		this.dtmfOutput = new DtmfOutput(scheduler, this);
		this.sequenceNumber = 0;
		this.dtmfSequenceNumber = 0;
		this.dtmfTimestamp = -1;
		this.timestamp = -1;
		this.formats = null;
		this.secure = false;
	}
	
	public void setFormatMap(final RTPFormats rtpFormats) {
		this.dtmfSupported = rtpFormats.contains(AVProfile.telephoneEventsID);
		this.formats = rtpFormats;
	}
	
	public RTPOutput getRtpOutput() {
		return rtpOutput;
	}
	
	public DtmfOutput getDtmfOutput() {
		return dtmfOutput;
	}
	
	public void enableSrtp(final DtlsHandler handler) {
		this.secure = true;
		this.dtlsHandler = handler;
	}

	public void disableSrtp() {
		this.secure = false;
		this.dtlsHandler = null;
	}
	
	public void activate() {
		this.rtpOutput.activate();
		this.dtmfOutput.activate();
	}
	
	public void deactivate() {
		this.rtpOutput.deactivate();
		this.dtmfOutput.deactivate();
		this.dtmfSupported = false;
	}
	
	public void setChannel(final DatagramChannel channel) {
		this.channel = channel;
	}
	
	private boolean isConnected() {
		return this.channel != null && this.channel.isConnected();
	}
	
	private void disconnect() throws IOException {
		if(this.channel != null) {
			this.channel.disconnect();
		}
	}
	
	public void reset() {
		deactivate();
		clear();
	}
	
	public void clear() {
		this.timestamp = -1;
		this.dtmfTimestamp = -1;
		// Reset format in case connection is reused.
		// Otherwise it would point to incorrect codec.
		this.currentFormat = null;
	}
	
	private void send(RtpPacket packet) throws IOException {
		// Do not send data while DTLS handshake is ongoing. WebRTC calls only.
		if(this.secure && !this.dtlsHandler.isHandshakeComplete()) {
			return;
		}

		// Secure RTP packet. WebRTC calls only.
		// SRTP handler returns null if an error occurs
		if (this.secure) {
			ByteBuffer srtpData = packet.dtlsEncodeToSend(this.dtlsHandler);
			if(srtpData != null) {
				channel.send(srtpData, channel.socket().getRemoteSocketAddress());
				statistics.onRtpSent(packet);
			} else {
				LOGGER.warn("Could not secure RTP packet! Packet dropped :  " + packet);
			}

		} else {
			packet.sendTo(channel);
			statistics.onRtpSent(packet);
		}
	}
	
	public void sendDtmf(Frame frame) {
		if (!this.dtmfSupported) {
			return;
		}

		//dtmfTimestamp is defined only in marked frames
		if (frame.isMark()) {
			this.dtmfSequenceNumber = 0;
			long localRtp = rtpClock.getLocalRtpTimeNoDrift();
			dtmfTimestamp = localRtp - (localRtp%160);
		} else {
			this.dtmfSequenceNumber++;
		}

		int newSeq = this.dtmfSequenceNumber + 1 + (int)(dtmfTimestamp/160);
		if (newSeq > this.sequenceNumber) this.sequenceNumber = newSeq;
		else this.sequenceNumber++;

		try {
			RtpPacket oobPacket = RtpPacket.outgoing(
					this.channel.getLocalAddress()
					, this.channel.getRemoteAddress()
					, frame.isMark()
					, AVProfile.telephoneEventsID
					, this.sequenceNumber
					, dtmfTimestamp
					, this.statistics.getSsrc()
					, frame.getData()
					, frame.getOffset()
					, frame.getLength()
			);

			if(isConnected()) {
				send(oobPacket);
			}
		} catch (PortUnreachableException e) {
			try {
				// icmp unreachable received
				// disconnect and wait for new packet
				disconnect();
			} catch (IOException ex) {
				LOGGER.error(ex.getMessage(), ex);
			}
		} catch (Exception e) {
			LOGGER.error(e.getMessage(), e);
		}
	}
	
	public void send(Frame frame) {
		// discard frame if format is unknown
		if (frame == null || frame.getFormat() == null) {
			return;
		}

		// determine current RTP format if it is unknown
		if (currentFormat == null || !currentFormat.getFormat().matches(frame.getFormat())) {
			currentFormat = formats.getRTPFormat(frame.getFormat());
			// discard packet if format is still unknown
			if (currentFormat == null) {
				return;
			}
			// update clock rate
			rtpClock.setClockRate(currentFormat.getClockRate());
		}

        timestamp = rtpClock.getLocalRtpTimeNoDrift();
		int newSeq = 1 + (int)(timestamp/160);
		if (newSeq > this.sequenceNumber) this.sequenceNumber = newSeq;
		else this.sequenceNumber++;

		try {
			RtpPacket rtpPacket = RtpPacket.outgoing(
					this.channel.getLocalAddress()
					, this.channel.getRemoteAddress()
					, false
					, currentFormat.getID()
					, this.sequenceNumber
					, timestamp - (timestamp%160)
					, this.statistics.getSsrc()
					, frame.getData()
					, frame.getOffset()
					, frame.getLength()
			);

			if (isConnected()) {
				send(rtpPacket);
			}
		} catch (PortUnreachableException e) {
			// icmp unreachable received
			// disconnect and wait for new packet
			try {
				disconnect();
			} catch (IOException ex) {
				LOGGER.error(ex.getMessage(), ex);
			}
		} catch (Exception e) {
			LOGGER.error(e.getMessage(), e);
		}
	}

}
