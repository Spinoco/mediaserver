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

import java.io.Serializable;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

import org.apache.logging.log4j.Logger;
import org.mobicents.media.server.io.sdp.format.RTPFormat;
import org.mobicents.media.server.io.sdp.format.RTPFormats;
import org.mobicents.media.server.scheduler.PriorityQueueScheduler;
import org.mobicents.media.server.spi.memory.Frame;

/**
 * Implements jitter buffer.
 * 
 * A jitter buffer temporarily stores arriving packets in order to minimize
 * delay variations. If packets arrive too late then they are discarded. A
 * jitter buffer may be mis-configured and be either too large or too small.
 * 
 * If a jitter buffer is too small then an excessive number of packets may be
 * discarded, which can lead to call quality degradation. If a jitter buffer is
 * too large then the additional delay can lead to conversational difficulty.
 * 
 * A typical jitter buffer configuration is 30mS to 50mS in size. In the case of
 * an adaptive jitter buffer then the maximum size may be set to 100-200mS. Note
 * that if the jitter buffer size exceeds 100mS then the additional delay
 * introduced can lead to conversational difficulty.
 *
 * @author oifa yulian
 */
public class JitterBuffer implements Serializable {
	
	private static final long serialVersionUID = -389930569631795779L;
	
	private final ReentrantLock LOCK = new ReentrantLock();

	private final double JC_BETA = .01d;
	private final double JC_GAMMA = .01d;

	//The underlying buffer size
    private static final int QUEUE_SIZE = 10;
    //the underlying buffer
    private ArrayList<Frame> queue = new ArrayList<Frame>(QUEUE_SIZE);
    
    //RTP clock
    private RtpClock rtpClock;
    //first received sequence number
    private long isn = -1;

    //allowed jitter
    private long jitterBufferSize;
    
    //packet arrival dead line measured on RTP clock.
    //initial value equals to infinity
    private long arrivalDeadLine = 0;

    //packet arrival dead line measured on RTP clock.
    //initial value equals to infinity
    private long droppedInRaw = 0;
    
    //The number of dropped packets
    private int dropCount;

    //known duration of media wich contains in this buffer.
    private volatile long duration;

    //buffer's monitor
    private BufferListener listener;

    private volatile boolean ready;
    
    /**
     * used to calculate network jitter.
     * currentTransit measures the relative time it takes for an RTP packet 
     * to arrive from the remote server to MMS
     */
    private long currentTransit = 0;
    
    /**
     * continuously updated value of network jitter 
     */
    private long currentJitter = 0;
    
    //transmission formats
    private RTPFormats rtpFormats = new RTPFormats();
    
    //currently used format
    private RTPFormat format;
    
    private Boolean useBuffer=true;
    
    private final static Logger logger = org.apache.logging.log4j.LogManager.getLogger(JitterBuffer.class);

    private long clockOffset = 0;
    private int adaptJittCompTimestamp = 0;
	private long jittCompTimestamp = 0;
	private double jitter = 0d;

	private PriorityQueueScheduler scheduler;

	private static AtomicLong recordingIndex = new AtomicLong();
	private AtomicReference<JitterBufferRTPDump> rtpDump = new AtomicReference<JitterBufferRTPDump>(null);

	// directory to dump to. If null, this indicates nothing has to be dump at all
	private Path dumpDir;
	private List<String> dumpConfig;

    /**
     * Creates new instance of jitter.
     * 
     * @param clock the rtp clock.
     */
    public JitterBuffer(RtpClock clock, int jitterBufferSize, PriorityQueueScheduler scheduler, Path dumpDir) {
        this.rtpClock = clock;
        this.jitterBufferSize = jitterBufferSize;
        this.scheduler = scheduler;
        if (dumpDir != null) {
			this.dumpDir = dumpDir;
			this.dumpConfig = JitterBufferRTPDump.getDumpConfig(dumpDir);
			if (this.dumpConfig != null) {
				this.rtpDump.set(new JitterBufferRTPDump(scheduler, recordingIndex.incrementAndGet(), dumpDir, this.dumpConfig));
			}
		}
    }

    private void initJitter(RtpPacket firstPacket) {
        long arrival = rtpClock.getLocalRtpTime();
        long firstPacketTimestamp = firstPacket.getTimestamp();
        currentTransit = arrival - firstPacketTimestamp;
        currentJitter = 0;
        clockOffset = currentTransit;
    }
    
	/**
	 * Calculates the current network jitter, which is an estimate of the
	 * statistical variance of the RTP data packet interarrival time:
	 * http://tools.ietf.org/html/rfc3550#appendix-A.8
	 */
	private void estimateJitter(RtpPacket newPacket) {
		long arrival = rtpClock.getLocalRtpTime();
		long newPacketTimestamp = newPacket.getTimestamp();
		long transit = arrival - newPacketTimestamp;
		long d = transit - currentTransit;
		if (d < 0) {
			d = -d;
		}

		currentTransit = transit;
		currentJitter += d - ((currentJitter + 8) >> 4);

		long diff = newPacketTimestamp - arrival;
    	double slide = (double)clockOffset*(1-JC_BETA) + (diff*JC_BETA);
		double gap = diff - slide;

    	gap = gap < 0 ? -gap : 0;
    	jitter = jitter*(1-JC_GAMMA) + (gap*JC_GAMMA);

		if (newPacket.getSeqNumber()%50 == 0) {
			adaptJittCompTimestamp = Math.max((int)jittCompTimestamp, (int)(2*jitter));
		}

		clockOffset = (long)slide;
	}
    
    /**
     * 
     * @return the current value of the network RTP jitter. The value is in normalized form as specified in RFC 3550 
     * http://tools.ietf.org/html/rfc3550#appendix-A.8
     */
    public long getEstimatedJitter() {
            long jitterEstimate = currentJitter >> 4; 
            // logger.info(String.format("Jitter estimated at %d. Current transit time is %d.", jitterEstimate, currentTransit));
            return jitterEstimate;
    }
    
    public void setFormats(RTPFormats rtpFormats) {
        this.rtpFormats = rtpFormats;
    }
    
    /**
     * Gets the interarrival jitter.
     *
     * @return the current jitter value.
     */
    public double getJitter() {
        return 0;
    }

    /**
     * Gets the maximum interarrival jitter.
     *
     * @return the jitter value.
     */
    public double getMaxJitter() {
        return 0;
    }
    
    /**
     * Get the number of dropped packets.
     * 
     * @return the number of dropped packets.
     */
    public int getDropped() {
        return dropCount;
    }
    
    public boolean bufferInUse()
    {
    	return this.useBuffer;
    }
    
    public void setBufferInUse(boolean useBuffer)
    {
    	this.useBuffer=useBuffer;
    }
    
    /**
     * Assigns listener for this buffer.
     * 
     * @param listener the listener object.
     */
    public void setListener(BufferListener listener) {
        this.listener = listener;
    }

	private long compensatedTimestamp(long userTimestamp) {
    	return userTimestamp+clockOffset-adaptJittCompTimestamp;
	}

    /**
     * Accepts specified packet
     *
     * @param packet the packet to accept
     */
	public void write(RtpPacket packet, RTPFormat format) {
		try {
			LOCK.lock();
			// checking format
			if (format == null) {
				logger.warn("No format specified. Packet dropped!");
				return;
			}



			if (this.format == null || this.format.getID() != format.getID()) {
				logger.info(
					"Format has been changed: " +
					", from: " + (this.format != null ? this.format.toString() : "null")  +
					", to: " + (format != null ? format.toString() : "null") +
					", localPeer: " + (packet.getLocalPeer() != null ? packet.getLocalPeer().toString() : "null") +
					", remotePeer: " + (packet.getRemotePeer() != null ? packet.getRemotePeer().toString() : "null") +
					", seq: " + packet.getSeqNumber() +
					", timestamp: " + packet.getTimestamp() +
					", csrc: " + packet.getContributingSource()
				);
				this.format = format;

				// update clock rate
				rtpClock.setClockRate(this.format.getClockRate());
				jittCompTimestamp = rtpClock.convertToRtpTime(60);
			}

			// if this is first packet then synchronize clock
			if (isn == -1) {
				rtpClock.synchronize(packet.getTimestamp());
				isn = packet.getSeqNumber();
				initJitter(packet);
			} else {
				estimateJitter(packet);
			}

			// drop outstanding packets
			// packet is outstanding if its timestamp of arrived packet is less
			// then consumer media time
			if (packet.getTimestamp() < this.arrivalDeadLine) {
				logger.warn(
					"drop packet: dead line=" + arrivalDeadLine +
							", packet time=" + packet.getTimestamp() +
							", seq=" + packet.getSeqNumber() +
							", payload length=" + packet.getPayloadLength() +
							", format=" + this.format.toString() +
							", csrc: " + packet.getContributingSource()
				);
				dropCount++;

				// checking if not dropping too much
				droppedInRaw++;
				if (droppedInRaw == QUEUE_SIZE / 2 || queue.size() == 0) {
					arrivalDeadLine = 0;
				} else {
					return;
				}
			}

			Frame f = packet.toFrame(rtpClock, this.format);
f.setDuration(rtpClock.convertToAbsoluteTime(f.getLength()));

			// dump the packet to capture if enabled so
			if (this.dumpConfig != null) {
				JitterBufferRTPDump dump = rtpDump.get();
				if (dump != null) dump.dump(packet, queue.size());
			}

			droppedInRaw = 0;

			// find correct position to insert a packet
			// use timestamp since its always positive
			int currIndex = queue.size() - 1;
			while (currIndex >= 0 && queue.get(currIndex).getTimestamp() > f.getTimestamp()) {
				currIndex--;
			}

			// check for duplicate packet
			if (currIndex >= 0 && queue.get(currIndex).getSequenceNumber() == f.getSequenceNumber()) {
				logger.warn(
						"dup packet found (dropping) packet time=" + packet.getTimestamp() +
								", seq=" + packet.getSeqNumber() +
								", payload length=" + packet.getPayloadLength() +
								", format=" + this.format.toString() +
								", csrc: " + packet.getContributingSource()
				);
				return;
			}

			queue.add(currIndex + 1, f);

			// recalculate duration of each frame in queue and overall duration
			// since we could insert the frame in the middle of the queue
			duration = 0;
			if (queue.size() > 1) {
				duration = queue.get(queue.size() - 1).getTimestamp() - queue.get(0).getTimestamp();
			}

//			for (int i = 0; i < queue.size() - 1; i++) {
//				// duration measured by wall clock
//				long d = queue.get(i + 1).getTimestamp() - queue.get(i).getTimestamp();
//				// in case of RFC2833 event timestamp remains same
//				queue.get(i).setDuration(d > 0 ? d : 0);
//			}

			// if overall duration is negative we have some mess here,try to
			// reset
			if (duration < 0 && queue.size() > 1) {
				logger.warn("Something messy happened. Reseting jitter buffer!");
				reset();
				return;
			}

			// overflow?
			// only now remove packet if overflow , possibly the same packet we just received
			if (queue.size() > QUEUE_SIZE) {
				logger.warn("Buffer overflow!" +
						" queue: " + queue.size() +
						", localPeer: " + (packet.getLocalPeer() != null ? packet.getLocalPeer().toString() : "null") +
						", remotePeer: " + (packet.getRemotePeer() != null ? packet.getRemotePeer().toString() : "null") +
						", seq: " + packet.getSeqNumber() +
						", timestamp: " + packet.getTimestamp() +
						", csrc: " + packet.getContributingSource()
				);
				dropCount++;
				queue.remove(0);
			}

			// check if this buffer already full
			if (!ready) {
				ready = !useBuffer || (queue.size() > 1);
				if (ready && listener != null) {
					listener.onFill();
				}
			}

		} finally {
			LOCK.unlock();
		}
	}     

    /**
     * Polls packet from buffer's head.
     *
     * @param timestamp the media time measured by reader
     * @return the media frame.
     */
    public Frame read(long timestamp) {
		try {
			LOCK.lock();
			if (queue.size() == 0) {
				this.ready = false;
				return null;
			}

			Frame frame = null;
			long rtpTime;

			long comp = compensatedTimestamp(rtpClock.getLocalRtpTime());

			while (queue.size() != 0) {
				frame = queue.remove(0);
				rtpTime = rtpClock.convertToRtpTime(frame.getTimestamp());

				if (comp <= rtpTime) {
					break;
				}
			}

			if (this.dumpConfig != null) {
				JitterBufferRTPDump dump = rtpDump.get();
				if (dump != null) {
					long seq = frame != null ? frame.getSequenceNumber() : -1;
					dump.suppliedDump(seq, queue.size());
				}
			}

			if (frame == null) {
				return null;
			}

			//buffer empty now? - change ready flag.
			if (queue.size() == 0) {
				this.ready = false;
			}

			arrivalDeadLine = rtpClock.convertToRtpTime(frame.getTimestamp() + frame.getDuration());

			//convert duration to nanoseconds
			frame.setDuration(frame.getDuration() * 1000000L);
			frame.setTimestamp(frame.getTimestamp() * 1000000L);

			return frame;
		} finally {
			LOCK.unlock();
		}
    }
    
    /**
     * Resets buffer.
     */
    public void reset() {
		try {
			LOCK.lock();
			queue = new ArrayList<>(QUEUE_SIZE);
		} finally {
			LOCK.unlock();
		}
    }

    private void restartRecording() {
		JitterBufferRTPDump previous = this.rtpDump.get();
		if (previous != null) previous.commit();

		this.dumpConfig = JitterBufferRTPDump.getDumpConfig(dumpDir);

		if (this.dumpConfig != null) {
			this.rtpDump.set(new JitterBufferRTPDump(this.scheduler, recordingIndex.incrementAndGet(), this.dumpDir, this.dumpConfig));
		}


	}
    
    public void restart() {
    	reset();
    	this.ready=false;
    	arrivalDeadLine = 0;
    	dropCount=0;
    	droppedInRaw=0;
    	format=null;
    	isn=-1;

    	//
		clockOffset = 0;
		adaptJittCompTimestamp = 0;
		jittCompTimestamp = 0;
		jitter = 0d;

		restartRecording();
    }
}
