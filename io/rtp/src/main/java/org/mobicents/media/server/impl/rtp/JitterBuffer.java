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
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

import org.apache.logging.log4j.Logger;
import org.mobicents.media.server.io.sdp.format.RTPFormat;
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

    private final int BUFFER_SIZE_MAX = 6;
    private final int BUFFER_SIZE_NOR = 3;
    private final int BUFFER_SIZE_MIN = 1;

	private final double SPEED_FAST = 0.3;
	private final double SPEED_NOR = 0.8;
	private final double SPEED_SLOW = 1.5;
	private final int NUM_FRAME_TIME_HISTORY = 60;

	private int avgFrameRate;
	private int lastFrameRate;
	private LinkedList<Long> decodedFrameTime = new LinkedList<>();

	//The underlying buffer size
    private static final int QUEUE_SIZE = 20;
    //the underlying buffer
    private ArrayList<Frame> queue = new ArrayList<Frame>(QUEUE_SIZE);
    
    //RTP clock
    private RtpClock rtpClock;
    //first received sequence number
    private long isn = -1;

    //packet arrival dead line measured on RTP clock.
    //initial value equals to infinity
    private long arrivalDeadLine = -1;


    //currently used format
    private RTPFormat format;
    
    private Boolean useBuffer=true;
    
    private final static Logger logger = org.apache.logging.log4j.LogManager.getLogger(JitterBuffer.class);

	private PriorityQueueScheduler scheduler;

	private static AtomicLong recordingIndex = new AtomicLong();
	private AtomicReference<JitterBufferRTPDump> rtpDump = new AtomicReference<JitterBufferRTPDump>(null);

	// directory to dump to. If null, this indicates nothing has to be dump at all
	private Path dumpDir;
	private List<String> dumpConfig;

	private long syncSource = -1;

    /**
     * Creates new instance of jitter.
     * 
     * @param clock the rtp clock.
     */
    public JitterBuffer(RtpClock clock, int jitterBufferSize, PriorityQueueScheduler scheduler, Path dumpDir) {
        this.rtpClock = clock;
        this.scheduler = scheduler;
		this.lastFrameRate = 50;
		this.avgFrameRate = 50;

        if (dumpDir != null) {
			this.dumpDir = dumpDir;
			this.dumpConfig = JitterBufferRTPDump.getDumpConfig(dumpDir);
			if (this.dumpConfig != null) {
				this.rtpDump.set(new JitterBufferRTPDump(scheduler, recordingIndex.incrementAndGet(), dumpDir, this.dumpConfig));
			}
		}
    }

    public void setBufferInUse(boolean useBuffer)
    {
    	this.useBuffer=useBuffer;
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
			}

			// if this is first packet then synchronize clock
			if (isn == -1) {
				rtpClock.synchronize(packet.getTimestamp());
				isn = packet.getSeqNumber();
				syncSource = packet.getSyncSource();
			}

			Frame f = packet.toFrame(rtpClock, this.format);
			f.setDuration(rtpClock.convertToAbsoluteTime(f.getLength()));

			// dump the packet to capture if enabled so
			if (this.dumpConfig != null) {
				JitterBufferRTPDump dump = rtpDump.get();
				if (dump != null) dump.dump(packet, queue.size());
			}

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
								", ssrc: " + packet.getSyncSource()
				);
				return;
			}

			if (currIndex == -1 && arrivalDeadLine != -1) {
				// drop outstanding packets
				// packet is outstanding if its timestamp of arrived packet is less
				// then consumer media time
				long arrivalDiff = packet.getTimestamp() - this.arrivalDeadLine;
				int maxDiff = packet.getPayloadLength() * 50; //1 second
				if (arrivalDiff < 0) {
					if (Math.abs(arrivalDiff) > maxDiff) {
						currIndex = queue.size() - 1;
					} else {
						logger.warn(
								"drop packet: dead line=" + arrivalDeadLine +
										", packet time=" + packet.getTimestamp() +
										", seq=" + packet.getSeqNumber() +
										", payload length=" + packet.getPayloadLength() +
										", format=" + this.format.toString() +
										", ssrc: " + packet.getSyncSource() +
										", arrivalDiff: " + arrivalDiff +
										", maxDiff: " + maxDiff
						);

						return;
					}
				}
			}

			if (syncSource != packet.getSyncSource()) {
				logger.warn("New SyncSource: " + packet.getSyncSource() +
						", old SyncSource: " + syncSource +
						", arrivalDeadline: " + arrivalDeadLine +
						", timestamp: " + packet.getTimestamp()
				);
				syncSource = packet.getSyncSource();
			}

			queue.add(currIndex + 1, f);

		} finally {
			LOCK.unlock();
		}
	}     

	public int getBufferSize() {
		return queue.size();
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
			if (!useBuffer) {
				if (queue.isEmpty()) {
					arrivalDeadLine = -1;

					return null;
				} else {
					Frame frame = queue.remove(0);

					arrivalDeadLine = rtpClock.convertToRtpTime(frame.getTimestamp() + frame.getDuration());

					//convert duration to nanoseconds
					frame.setDuration(frame.getDuration() * 1000000L);
					frame.setTimestamp(frame.getTimestamp() * 1000000L);

					return frame;
				}

			} else {

				int size = queue.size();

				long currentTime = timestamp / 1000000 + 20;
				long currentTimeDiff = 20;

				if (!decodedFrameTime.isEmpty()) {
					currentTimeDiff = currentTime - decodedFrameTime.peekFirst();
				}

				if (size < BUFFER_SIZE_MIN) {
//					System.out.println("SKIP MIN");
					return null;
				}

				else if (size < BUFFER_SIZE_NOR) {
					if (currentTimeDiff < (1000 * SPEED_SLOW / avgFrameRate)) {
//					System.out.println("SKIP NOR: " + currentTimeDiff + " " + (1000 * SPEED_SLOW / avgFrameRate));
						return null;
					}

				} else if (size < BUFFER_SIZE_MAX) {
					if (currentTimeDiff < (1000 * SPEED_NOR / avgFrameRate) &&
							currentTimeDiff < (1000 * SPEED_NOR / lastFrameRate)) {
//					System.out.println("SKIP < MAX: " + currentTimeDiff + " " + (1000 * SPEED_NOR / avgFrameRate) + " " + (1000 * SPEED_NOR / lastFrameRate));
						return null;
					}
				} else {
					if (currentTimeDiff < (1000 * SPEED_FAST / avgFrameRate) &&
							currentTimeDiff < (1000 * SPEED_FAST / lastFrameRate)) {
//						System.out.println("SKIP >= MAX: " + currentTimeDiff + " " + (1000 * SPEED_FAST / avgFrameRate) + " " + (1000 * SPEED_FAST / lastFrameRate));
						return null;
					}

					if (avgFrameRate > 49 && (currentTime % 200) == 0) {
						queue.remove(0);
					}

				}

				Frame frame = queue.remove(0);

				if (this.dumpConfig != null) {
					JitterBufferRTPDump dump = rtpDump.get();
					if (dump != null) {
						long seq = frame != null ? frame.getSequenceNumber() : -1;
						dump.suppliedDump(seq, queue.size());
					}
				}

				if (queue.isEmpty()) {
					arrivalDeadLine = -1;
				} else {
					//set arrival deadline for the next frame (in rtp time
					arrivalDeadLine = rtpClock.convertToRtpTime(frame.getTimestamp() + frame.getDuration());
				}

				//convert duration to nanoseconds
				frame.setDuration(frame.getDuration() * 1000000L);
				frame.setTimestamp(frame.getTimestamp() * 1000000L);

				lastFrameRate = (int) (1000.0 / currentTimeDiff);
				decodedFrameTime.push(currentTime);
				long frameRateDiff = (currentTime - decodedFrameTime.peekLast());

				int dftSize = decodedFrameTime.size()-1 ;
				if (dftSize == 0) dftSize = 1;

				if (frameRateDiff == 0) avgFrameRate = 50;
				else avgFrameRate = (int) (dftSize * 1000 / frameRateDiff);


				if (decodedFrameTime.size() >= NUM_FRAME_TIME_HISTORY) {
					decodedFrameTime.removeLast();
				}

				return frame;
			}
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
    	arrivalDeadLine = -1;
    	format=null;
    	isn=-1;
		syncSource=-1;

		lastFrameRate = 50;
		avgFrameRate = 50;

		decodedFrameTime.clear();

		restartRecording();
    }
}
