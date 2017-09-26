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

package org.mobicents.media.server.impl.resource.mediaplayer;

import java.io.IOException;
import org.mobicents.media.server.spi.format.Format;
import org.mobicents.media.server.spi.memory.Frame;

import javax.sound.sampled.UnsupportedAudioFileException;

/**
 *
 * @author kulikov
 */
public interface Track {
    /**
     * Gets the format of this audio stream.
     * 
     * @return the format object.
     */
    public Format getFormat();
    
    /**
     * Gets the current media time.
     * 
     * @return time value expressed in milliseconds.
     */
    public long getMediaTime();
    
    /**
     * Rewinds track to the specified timestamp.
     * 
     * @param  timestamp the value of the time in nanosecond
     */
    public void setMediaTime(long timestamp);
    
    /**
     * Gets duration of this track.
     * 
     * @return duration expressed in nanoseconds.
     */
    public long getDuration();
    
    /**
     * Fills buffer with next portion of media data.
     * 
     * @param timestamp - current media time expressed in nanoseconds
     */
    public Frame process(long timestamp) throws IOException;

    /** opens the stream, makes it ready for playback. implememntation must be idempotent **/
    public void open() throws IOException, UnsupportedAudioFileException;

    /**
     * Closes this stream.
     */
    public void close();


    /** returns minimum amount of samples required before new buffered load is executed **/
    public int minSampleTreshold();

    /** max samples to load in buffer **/
    public int maxSamples();

    /** gets size of frame **/
    public int frameSize();

}
