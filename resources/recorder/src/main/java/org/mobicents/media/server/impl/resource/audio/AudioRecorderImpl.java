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

package org.mobicents.media.server.impl.resource.audio;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

import org.apache.logging.log4j.Logger;
import org.mobicents.media.ComponentType;
import org.mobicents.media.server.component.audio.AudioOutput;
import org.mobicents.media.server.component.oob.OOBOutput;
import org.mobicents.media.server.impl.AbstractSink;
import org.mobicents.media.server.scheduler.EventQueueType;
import org.mobicents.media.server.scheduler.PriorityQueueScheduler;
import org.mobicents.media.server.scheduler.Task;
import org.mobicents.media.server.spi.dtmf.DtmfTonesData;
import org.mobicents.media.server.spi.format.AudioFormat;
import org.mobicents.media.server.spi.format.FormatFactory;
import org.mobicents.media.server.spi.format.Formats;
import org.mobicents.media.server.spi.listener.Listeners;
import org.mobicents.media.server.spi.listener.TooManyListenersException;
import org.mobicents.media.server.spi.memory.Frame;
import org.mobicents.media.server.spi.memory.Memory;
import org.mobicents.media.server.spi.pooling.PooledObject;
import org.mobicents.media.server.spi.recorder.Recorder;
import org.mobicents.media.server.spi.recorder.RecorderEvent;
import org.mobicents.media.server.spi.recorder.RecorderListener;

/**
 * @author yulian oifa
 * @author Henrique Rosa (henrique.rosa@telestax.com)
 * @author Pavel Chlupacek (pchlupacek)
 */
public class AudioRecorderImpl extends AbstractSink implements Recorder, PooledObject {

    private static final long serialVersionUID = -5290778284867189598L;

    private final static AudioFormat LINEAR = FormatFactory.createAudioFormat("linear", 8000, 16, 1);
    private final static Formats formats = new Formats();

    private final static int SILENCE_LEVEL = 10;

    static {
        formats.add(LINEAR);
    }

    private String recordDir;
    private AtomicReference<RecorderFileSink> sink = new AtomicReference<>(null);

    // if set ti true the record will terminate recording when silence detected
    private long postSpeechTimer = -1L;
    private long preSpeechTimer = -1L;

    // samples
    private ByteBuffer byteBuffer = ByteBuffer.allocateDirect(8192);
    private byte[] data;
    private int offset;
    private int len;

    private KillRecording killRecording;
    private Heartbeat heartbeat;

    private long lastPacketData = 0, startTime = 0;

    private PriorityQueueScheduler scheduler;

    // maximum recrding time. -1 means until stopped.
    private long maxRecordTime = -1;

    // listener
    private Listeners<RecorderListener> listeners = new Listeners<RecorderListener>();

    // events
    private RecorderEventImpl recorderStarted;
    private RecorderEventImpl recorderStopped;
    private RecorderEventImpl recorderFailed;

    // event sender task
    private EventSender eventSender;

    // event qualifier
    private int qualifier;

    private boolean speechDetected = false;

    private AudioOutput output;
    private OOBOutput oobOutput;
    private OOBRecorder oobRecorder;

    private static final Logger logger = org.apache.logging.log4j.LogManager.getLogger(AudioRecorderImpl.class);

    // Hence the recorded runs in OUTPUT task queue, then this shall assure we won't
    // touch any IO in the OUTPUT Queue and instead shift it to recording cycle
    // essentially this is there to guarantee ordering of the packets so the tasks that flush
    // the content of the file won't get reordered
    private AtomicReference<ConcurrentLinkedQueue<Frame>> writeBuff = new AtomicReference<ConcurrentLinkedQueue<Frame>>(null);

    // Guarantees we perform only one write operation at any time
    private ReentrantLock lock = new ReentrantLock();

    public AudioRecorderImpl(PriorityQueueScheduler scheduler) {
        super("recorder");
        this.scheduler = scheduler;

        killRecording = new KillRecording();

        // initialize events
        recorderStarted = new RecorderEventImpl(RecorderEvent.START, this);
        recorderStopped = new RecorderEventImpl(RecorderEvent.STOP, this);
        recorderFailed = new RecorderEventImpl(RecorderEvent.FAILED, this);

        // initialize event sender task
        eventSender = new EventSender();
        heartbeat = new Heartbeat();

        output = new AudioOutput(scheduler, ComponentType.RECORDER.getType());
        output.join(this);

        oobOutput = new OOBOutput(scheduler, ComponentType.RECORDER.getType());
        oobRecorder = new OOBRecorder();
        oobOutput.join(oobRecorder);
    }

    public AudioOutput getAudioOutput() {
        return this.output;
    }

    public OOBOutput getOOBOutput() {
        return this.oobOutput;
    }

    @Override
    public void activate() {
        this.lastPacketData = scheduler.getClock().getTime();
        this.startTime = scheduler.getClock().getTime();

        output.start();
        oobOutput.start();
        if (this.postSpeechTimer > 0 || this.preSpeechTimer > 0 || this.maxRecordTime > 0) {
            scheduler.submitHeartbeat(this.heartbeat);
        }

        // send event
        fireEvent(recorderStarted);
    }

    @Override
    public void deactivate() {
        if (!this.isStarted()) {
            return;
        }

        try {
            output.stop();
            oobOutput.stop();
            this.maxRecordTime = -1;
            this.lastPacketData = 0;
            this.startTime = 0;

            this.heartbeat.cancel();

            // deactivate can be concurrently invoked from  multiple threads (MediaGroup, KillRecording for example).
            // to make sure the sink is closed only once, we set the sink ref to null and proceed to commit only if obtained reference is not null.
            RecorderFileSink snk = sink.getAndSet(null);
            ConcurrentLinkedQueue<Frame> wb = writeBuff.getAndSet(null);
            if (snk != null && wb != null) {
                scheduler.submit(new FlushRecording(snk, wb,  this.lock), EventQueueType.RECORDING);
            }

        } catch (Exception e) {
            logger.error("Error writing to file", e);
        } finally {
            // send event
            recorderStopped.setQualifier(qualifier);
            fireEvent(recorderStopped);

            // clean qualifier
            this.qualifier = 0;
            this.maxRecordTime = -1L;
            this.postSpeechTimer = -1L;
            this.preSpeechTimer = -1L;
            this.speechDetected = false;
        }
    }

    @Override
    public void setPreSpeechTimer(long value) {
        this.preSpeechTimer = value;
    }

    @Override
    public void setPostSpeechTimer(long value) {
        this.postSpeechTimer = value;
    }

    @Override
    public void setMaxRecordTime(long maxRecordTime) {
        this.maxRecordTime = maxRecordTime;
    }

    /**
     * Fires specified event
     * 
     * @param event the event to fire.
     */
    private void fireEvent(RecorderEventImpl event) {
        eventSender.event = event;
        scheduler.submit(eventSender, EventQueueType.RECORDING);
    }

    @Override
    public void onMediaTransfer(Frame frame) throws IOException {

        RecorderFileSink snk = sink.get();
        ConcurrentLinkedQueue<Frame> wb = writeBuff.get();

        if (snk != null && wb != null) {
            wb.offer(frame.clone());
            scheduler.submit(new WriteSample(snk, wb, this.lock), EventQueueType.RECORDING);
        }

        if (this.postSpeechTimer > 0 || this.preSpeechTimer > 0) {
            // detecting silence
            if (!this.checkForSilence(data, offset, len)) {
                this.lastPacketData = scheduler.getClock().getTime();
                this.speechDetected = true;
            }
        } else {
            this.lastPacketData = scheduler.getClock().getTime();
        }
    }

    @Override
    public void setRecordDir(String recordDir) {
        this.recordDir = recordDir;
    }

    @Override
    public void setRecordFile(String uri, boolean append) throws IOException {
        // calculate the full path
        String path = uri.startsWith("file:") ? uri.replaceAll("file://", "") : this.recordDir + "/" + uri;
        Path file = Paths.get(path);

        // initialize buffer and recording
        RecorderFileSink snk = sink.getAndSet(new RecorderFileSink(file,append));
        writeBuff.getAndSet(new ConcurrentLinkedQueue<Frame>());

        if (snk != null) {
            logger.error("Sink for the recording is not cleaned properly, found " + snk);
        }
    }

    /**
     * Checks does the frame contains sound or silence.
     * 
     * @param data buffer with samples
     * @param offset the position of first sample in buffer
     * @param len the number if samples
     * @return true if silence detected
     */
    private boolean checkForSilence(byte[] data, int offset, int len) {
        for (int i = offset; i < len - 1; i += 2) {
            int s = (data[i] & 0xff) | (data[i + 1] << 8);
            if (s > SILENCE_LEVEL) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void addListener(RecorderListener listener) throws TooManyListenersException {
        listeners.add(listener);
    }

    @Override
    public void removeListener(RecorderListener listener) {
        listeners.remove(listener);
    }

    @Override
    public void clearAllListeners() {
        listeners.clear();
    }
    
    @Override
    public void checkIn() {
        // clear listeners
        clearAllListeners();

        // clean buffers
        this.byteBuffer.clear();
        this.data = null;
        this.offset = 0;
        this.len = 0;
        
        // reset internal state
        this.recordDir = "";
        this.postSpeechTimer = -1L;
        this.preSpeechTimer = -1L;
        this.lastPacketData = 0L;
        this.startTime = 0L;
        this.maxRecordTime = -1L;
        this.qualifier = 0;
        this.speechDetected = false;
    }

    @Override
    public void checkOut() {
        // TODO Auto-generated method stub
    }

    /**
     * Asynchronous recorder stopper.
     */
    private class KillRecording extends Task {

        public KillRecording() {
            super();
        }

        @Override
        public long perform() {
            deactivate();
            return 0;
        }

        public EventQueueType getQueueType() {
            return EventQueueType.RECORDING;
        }
    }

    /**
     * Asynchronous recorder stopper.
     */
    private class EventSender extends Task {

        protected RecorderEventImpl event;

        public EventSender() {
            super();
        }

        @Override
        public long perform() {
            listeners.dispatch(event);
            return 0;
        }

        public EventQueueType getQueueType() {
            return EventQueueType.MGCP_SIGNALLING;
        }
    }

    /**
     * Heartbeat
     */
    private class Heartbeat extends Task {

        public Heartbeat() {
            super();
        }

        @Override
        public long perform() {
            long currTime = scheduler.getClock().getTime();

            if (preSpeechTimer > 0 && !speechDetected && currTime - lastPacketData > preSpeechTimer) {
                qualifier = RecorderEvent.NO_SPEECH;
                scheduler.submit(killRecording, EventQueueType.RECORDING);
                return 0;
            }

            if (postSpeechTimer > 0 && speechDetected && currTime - lastPacketData > postSpeechTimer) {
                qualifier = RecorderEvent.NO_SPEECH;
                scheduler.submit(killRecording, EventQueueType.RECORDING);
                return 0;
            }

            // check max time and stop recording if exeeds limit
            if (maxRecordTime > 0 && currTime - startTime >= maxRecordTime) {
                // set qualifier
                qualifier = RecorderEvent.MAX_DURATION_EXCEEDED;
                scheduler.submit(killRecording, EventQueueType.RECORDING);
                return 0;
            }

            scheduler.submitHeartbeat(this);
            return 0;
        }

        @Override
        public EventQueueType getQueueType() {
            return EventQueueType.HEARTBEAT;
        }

    }

    private class OOBRecorder extends AbstractSink {

        private static final long serialVersionUID = -7570027234464617359L;

        private byte currTone = (byte) 0xFF;
        private long latestSeq = 0;

        private boolean hasEndOfEvent = false;
        private long endSeq = 0;
 

        public OOBRecorder() {
            super("oob recorder");
        }

        @Override
        public void onMediaTransfer(Frame buffer) throws IOException {
            byte[] data = buffer.getData();
            if (data.length != 4) {
                return;
            }

            boolean endOfEvent = false;
            endOfEvent = (data[1] & 0X80) != 0;

            // lets ignore end of event packets
            if (endOfEvent) {
                hasEndOfEvent = true;
                endSeq = buffer.getSequenceNumber();
                return;
            }

            // lets update sync data , allowing same tone come after 160ms from previous tone , not including end of tone
            if (currTone == data[0]) {
                if (hasEndOfEvent) {
                    if (buffer.getSequenceNumber() <= endSeq && buffer.getSequenceNumber() > (endSeq - 8)) {
                        // out of order , belongs to same event
                        // if comes after end of event then its new one
                        return;
                    }
                } else if ((buffer.getSequenceNumber() < (latestSeq + 8)) && buffer.getSequenceNumber() > (latestSeq - 8)) {
                    if (buffer.getSequenceNumber() > latestSeq) {
                        latestSeq = buffer.getSequenceNumber();
                    }
                    return;
                }
            }

            hasEndOfEvent = false;
            endSeq = 0;

            latestSeq = buffer.getSequenceNumber();
            currTone = data[0];

            RecorderFileSink snk = sink.get();
            ConcurrentLinkedQueue<Frame> wb = writeBuff.get();
            if (snk != null && wb != null) {
                byte[] sample = DtmfTonesData.buffer[currTone];
                Frame f = Memory.allocate(sample.length);
                System.arraycopy(sample, 0, f.getData(), 0, sample.length);
                f.setOffset(0);
                f.setLength(sample.length);
                wb.offer(f);
                scheduler.submit(new WriteSample(snk, wb,  AudioRecorderImpl.this.lock), EventQueueType.RECORDING);
            }
        }

        @Override
        public void activate() {
        }

        @Override
        public void deactivate() {
        }
    }


    /**
     * Write samples in supplied buff to sink
     * @param snk           Sink where to write samples to
     * @param writeBuff     Buffer where to read samples from
     */
    private static void writeSamples(RecorderFileSink snk, ConcurrentLinkedQueue<Frame> writeBuff) {
        Frame f = writeBuff.poll();
        while (f != null) {
            try {
                snk.write(ByteBuffer.wrap(
                    f.getData()
                    , f.getOffset()
                    , f.getLength()
                ));
                f.recycle();
            } catch (Exception ex) {
                logger.error("Failed to write sample to file: " + snk + " ( " + f + " )", ex);
            }
            f = writeBuff.poll();
        }
    }


    // Assures in timely manner on RECORDING queue, that the samples are written to disk
    private class WriteSample extends Task {
        private RecorderFileSink snk;
        private ConcurrentLinkedQueue<Frame> writeBuff;
        private ReentrantLock lock;

        public WriteSample(RecorderFileSink snk, ConcurrentLinkedQueue<Frame> writeBuff, ReentrantLock lock) {
            this.snk = snk;
            this.writeBuff = writeBuff;
            this.lock = lock;
        }

        @Override
        public EventQueueType getQueueType() {
            return EventQueueType.RECORDING;
        }

        @Override
        public long perform() {
            if (lock.tryLock()) {
                try {
                    writeSamples(this.snk, this.writeBuff);
                } finally {
                    lock.unlock();
                }
            }
            return 0;
        }
    }

    // when recording is completed, this assures file is closed and sink is committed.
    private class FlushRecording extends Task {
        private RecorderFileSink snk;
        private ConcurrentLinkedQueue<Frame> writeBuff;
        private ReentrantLock lock;

        public FlushRecording(RecorderFileSink snk, ConcurrentLinkedQueue<Frame> writeBuff, ReentrantLock lock) {
            this.snk = snk;
            this.writeBuff = writeBuff;
            this.lock = lock;
        }

        @Override
        public EventQueueType getQueueType() {
            return EventQueueType.RECORDING;
        }

        @Override
        public long perform() {
            lock.lock(); // unfortunately here we must block as we don't get mulitple guaranteed invocations of this
            try {
                writeSamples(this.snk, this.writeBuff);
                try {
                    this.snk.commit();
                } catch (IOException e) {
                    logger.error("Failed to commit Recording Sink: " + this.snk, e);
                }
            } finally {
                lock.unlock();
            }
            return 0;
        }
    }

}
