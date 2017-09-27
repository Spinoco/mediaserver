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

package org.mobicents.media.server.impl.resource.mediaplayer.audio;

import java.io.IOException;
import java.lang.reflect.Array;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Arrays;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

import org.apache.log4j.Logger;
import org.mobicents.media.ComponentType;
import org.mobicents.media.server.component.Dsp;
import org.mobicents.media.server.component.audio.AudioInput;
import org.mobicents.media.server.impl.AbstractSource;
import org.mobicents.media.server.impl.resource.mediaplayer.Track;
import org.mobicents.media.server.impl.resource.mediaplayer.audio.gsm.GsmTrackImpl;
import org.mobicents.media.server.impl.resource.mediaplayer.audio.mpeg.AMRTrackImpl;
import org.mobicents.media.server.impl.resource.mediaplayer.audio.tone.ToneTrackImpl;
import org.mobicents.media.server.impl.resource.mediaplayer.audio.tts.TtsTrackImpl;
import org.mobicents.media.server.impl.resource.mediaplayer.audio.wav.WavTrackImpl;
import org.mobicents.media.server.scheduler.EventQueueType;
import org.mobicents.media.server.scheduler.PriorityQueueScheduler;
import org.mobicents.media.server.scheduler.Task;
import org.mobicents.media.server.spi.ResourceUnavailableException;
import org.mobicents.media.server.spi.dsp.Processor;
import org.mobicents.media.server.spi.format.AudioFormat;
import org.mobicents.media.server.spi.format.FormatFactory;
import org.mobicents.media.server.spi.listener.Listeners;
import org.mobicents.media.server.spi.listener.TooManyListenersException;
import org.mobicents.media.server.spi.memory.Frame;
import org.mobicents.media.server.spi.memory.Memory;
import org.mobicents.media.server.spi.player.Player;
import org.mobicents.media.server.spi.player.PlayerListener;
import org.mobicents.media.server.spi.pooling.PooledObject;
import org.mobicents.media.server.spi.resource.TTSEngine;

import javax.sound.sampled.UnsupportedAudioFileException;

/**
 * @author yulian oifa
 * @author Henrique Rosa (henrique.rosa@telestax.com)
 */
public class AudioPlayerImpl extends AbstractSource implements Player, TTSEngine, PooledObject {

    private static final long serialVersionUID = 8321615909592642344L;

    private final static Logger log = Logger.getLogger(AudioPlayerImpl.class);

    // define natively supported formats
    private final static AudioFormat LINEAR = FormatFactory.createAudioFormat("linear", 8000, 16, 1);
    private final static long period = 20000000L;
    private final static int packetSize = (int) (period / 1000000) * LINEAR.getSampleRate() / 1000 * LINEAR.getSampleSize() / 8;

    // Media Components
    private Processor dsp;
    private final AudioInput input;

    // audio track and buffer
    private AtomicReference<Track> track = new AtomicReference<Track>(null);

    // we keep about 1s of decoded frames in buffer that are decoded on non-realtime thread.
    // then on the real time thread, in evolve, this is used to get next frame in timely 20ms samples
    private AtomicReference<ConcurrentLinkedQueue<Frame>> buff = new AtomicReference<ConcurrentLinkedQueue<Frame>>(null);

    // prevent to read concurrently, when one read did not finished in 20ms window.
    private ReentrantLock readLock = new ReentrantLock();


    private int volume;
    private String voiceName = "kevin";

    // Listeners
    private final Listeners<PlayerListener> listeners;



    /**
     * Creates new instance of the Audio player.
     * 
     * @param name the name of the AudioPlayer to be created.
     * @param scheduler EDF job scheduler
     */
    public AudioPlayerImpl(String name, PriorityQueueScheduler scheduler) {
        super(name, scheduler, EventQueueType.PLAYBACK);
        this.input = new AudioInput(ComponentType.PLAYER.getType(), packetSize);
        this.listeners = new Listeners<PlayerListener>();
        this.connect(this.input);
    }

    public AudioInput getAudioInput() {
        return this.input;
    }

    /**
     * Assigns the digital signaling processor of this component. The DSP allows to get more output formats.
     *
     * @param dsp the dsp instance
     */
    public void setDsp(Processor dsp) {
        this.dsp = dsp;
    }

    /**
     * Gets the digital signaling processor associated with this media source
     *
     * @return DSP instance.
     */
    public Processor getDsp() {
        return this.dsp;
    }

    @Override
    public void setURL(String passedURI) throws ResourceUnavailableException, MalformedURLException {
        // close previous track if was opened
        Track track = this.track.getAndSet(null);
        ConcurrentLinkedQueue<Frame> inBuff = this.buff.getAndSet(null);


        if (track != null && inBuff != null) {
            scheduler.submit(new CloseTrack(track), EventQueueType.PLAYBACK);
        }

        // let's disallow to assign file is player is not connected
        if (!this.isConnected()) {
            throw new IllegalStateException("Component should be connected");
        }

        URL targetURL;
        // now using extension we have to determine the suitable stream parser
        int pos = passedURI.lastIndexOf('.');

        // extension is not specified?
        if (pos == -1) {
            throw new MalformedURLException("Unknown file type: " + passedURI);
        }

        String ext = passedURI.substring(pos + 1).toLowerCase();
        targetURL = new URL(passedURI);

        // creating required extension
        // note that any constructors her must not allocate any resources at this stage, rather they shall only
        // get configuration and prepare for buffered read in near future
        try {
            // check scheme, if its file, we should try to create dirs
            if (ext.matches(Extension.WAV)) {
                track = new WavTrackImpl(targetURL);
            } else if (ext.matches(Extension.GSM)) {
                track = new GsmTrackImpl(targetURL);
            } else if (ext.matches(Extension.TONE)) {
                track = new ToneTrackImpl(targetURL);
            } else if (ext.matches(Extension.TXT)) {
                track = new TtsTrackImpl(targetURL, voiceName, null);
            } else if (ext.matches(Extension.MOV) || ext.matches(Extension.MP4) || ext.matches(Extension.THREE_GP)) {
                track = new AMRTrackImpl(targetURL);
            } else {
                throw new ResourceUnavailableException("Unknown extension: " + passedURI);
            }
        } catch (Exception e) {
            throw new ResourceUnavailableException(e);
        }

        // set track and set buffers.
        this.track.set(track);
        ConcurrentLinkedQueue<Frame> buff = new ConcurrentLinkedQueue<Frame>();
        this.buff.set(buff);

        this.scheduler.submit(new ReadToBuffer(track, buff, getDsp(), this.readLock), EventQueueType.PLAYBACK);

        // update duration
        this.duration = track.getDuration();
    }

    @Override
    public void activate() {
        if (track == null) {
            throw new IllegalStateException("The media source is not specified");
        }
        start();

        listeners.dispatch(new AudioPlayerEvent(this, AudioPlayerEvent.START));
    }

    @Override
    public void deactivate() {
        stop();
        Track track = this.track.getAndSet(null);
        this.buff.set(null);
        if (track != null) scheduler.submit(new CloseTrack(track), EventQueueType.PLAYBACK);
    }

    @Override
    protected void stopped() {
        listeners.dispatch(new AudioPlayerEvent(this, AudioPlayerEvent.STOP));
    }

    @Override
    protected void completed() {
        super.completed();
        listeners.dispatch(new AudioPlayerEvent(this, AudioPlayerEvent.STOP));
    }

    @Override
    public Frame evolve(long timestamp) {
        Track track = this.track.get();
        ConcurrentLinkedQueue<Frame> buff = this.buff.get();
        if (track != null && buff != null) {
            Frame f = buff.poll();
            if (f != null) {
                f.setTimestamp(timestamp);
                if (f.isEOM()) {
                    this.buff.set(null); // causes to stop any further reading
                    this.track.set(null);
                    scheduler.submit(new CloseTrack(track), EventQueueType.PLAYBACK);
                }
                else {
                    scheduler.submit(new ReadToBuffer(track, buff, getDsp(), this.readLock), EventQueueType.PLAYBACK); // causes to check each 20ms if we have enough samples read from the source.
                }
            } else {
                // we have buffer, that means eom not yet received but we have drained media so much...
                scheduler.submit(new ReadToBuffer(track, buff, getDsp(), this.readLock), EventQueueType.PLAYBACK); // causes to check each 20ms if we have enough samples read from the source.
            }
            if (f == null) {
                // produce empty silent sample in LINEAR PCM hence the media are not yet done, neither samples are available.
                f = Memory.allocate(320);       // Size of PCM LINER sample
                Arrays.fill(f.getData(), (byte)0);      // assure sample is silent
                f.setOffset(0);
                f.setLength(320);
                f.setTimestamp(timestamp);
                f.setDuration(20000000); // 20 ms
                f.setSequenceNumber(0);
                f.setEOM(false);
                f.setFormat(LINEAR);
                f.setHeader(null);
            }

            return f;
        } else {
            return null;
        }

    }

    @Override
    public void setVoiceName(String voiceName) {
        this.voiceName = voiceName;
    }

    @Override
    public String getVoiceName() {
        return voiceName;
    }

    @Override
    public void setVolume(int volume) {
        this.volume = volume;
    }

    @Override
    public int getVolume() {
        return volume;
    }

    @Override
    public void setText(String text) {
        // seems this is not used anywhere
        //track = new TtsTrackImpl(text, voiceName, null);

    }

    @Override
    public void addListener(PlayerListener listener) throws TooManyListenersException {
        listeners.add(listener);
    }

    @Override
    public void removeListener(PlayerListener listener) {
        listeners.remove(listener);
    }

    @Override
    public void clearAllListeners() {
        listeners.clear();
    }

    @Override
    public void checkIn() {
        clearAllListeners();
        track = null;
    }

    @Override
    public void checkOut() {
        // TODO Auto-generated method stub

    }


    private class CloseTrack extends Task {
        private Track track ;

        public CloseTrack(Track track) {
            this.track = track;
        }

        @Override
        public EventQueueType getQueueType() {
            return EventQueueType.PLAYBACK;
        }

        @Override
        public long perform() {
            this.track.close();
            return 0;
        }
    }


    // Reads samples to buffer (about 1s) so we don't need to perform IO on Real time thread.
    private class ReadToBuffer extends Task {
        private Track track;
        private ConcurrentLinkedQueue<Frame> destination;
        private Processor dsp;
        private ReentrantLock lock;

        public ReadToBuffer(Track track, ConcurrentLinkedQueue<Frame> destination, Processor dsp, ReentrantLock lock) {
            this.track = track;
            this.destination = destination;
            this.dsp = dsp;
            this.lock = lock;
        }

        @Override
        public EventQueueType getQueueType() {
            return EventQueueType.PLAYBACK;
        }

        @Override
        public long perform() {
            if (lock.tryLock()) {
                try {
                    int min = track.minSampleTreshold();
                    int max = track.maxSamples();
                    int curr = destination.size(); // note this is not constant... but shall be always like 100 frames at max...
                    if (curr <= min) {
                        try {
                            track.open();
                            while (curr < max) {

                                    Frame f = track.process(0);
                                    if (f != null) {
                                        if (dsp != null) {
                                            // decode frame if necessary...
                                            f = dsp.process(f, f.getFormat(), LINEAR);
                                        }
                                        destination.offer(f);
                                    }
                                    if (f != null && f.isEOM()) curr = max;

                                curr++;
                            }
                        } catch (Exception e) {
                            log.error("Failed to get next sample from the audio prompt:" + track, e);

                            Frame eomF = Memory.allocate(track.frameSize());
                            eomF.setEOM(true);
                            eomF.setFormat(track.getFormat());
                            eomF.setOffset(0);
                            eomF.setLength(track.frameSize());
                            destination.offer(eomF);

                            track.close();

                        }
                    }
                } finally {
                    lock.unlock();
                }
            }


            return 0;
        }
    }
}
