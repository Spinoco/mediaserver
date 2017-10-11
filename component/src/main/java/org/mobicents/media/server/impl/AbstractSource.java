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

package org.mobicents.media.server.impl;


import org.apache.log4j.Logger;
import org.mobicents.media.MediaSource;
import org.mobicents.media.server.scheduler.MetronomeTask;
import org.mobicents.media.server.scheduler.PriorityQueueScheduler;
import org.mobicents.media.server.spi.memory.Frame;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The base implementation of the Media source.
 * 
 * <code>AbstractSource</code> and <code>AbstractSink</code> are implement general wirring contruct. All media
 * components have to extend one of these classes.
 * 
 * @author Oifa Yulian
 */
public abstract class AbstractSource extends BaseComponent implements MediaSource {

	private static final long serialVersionUID = 3157479112733053482L;

	//transmission statisctics
    private volatile long txPackets;
    private volatile long txBytes;
    
    //shows if component is started or not.
    private AtomicBoolean started = new AtomicBoolean(false);

    //stream synchronization flag
    private volatile boolean isSynchronized;

    //local media time
    private volatile long timestamp = 0;
    
    //initial media time
    private long initialOffset;
    
    //frame sequence number
    private long sn = 1;

    //scheduler instance
    protected PriorityQueueScheduler scheduler;

    //media generator
    private final Worker worker;

  //duration of media stream in nanoseconds
    protected long duration = -1;

    //intial delay for media processing
    private long initialDelay = 0;
    
    //media transmission pipe
    protected AbstractSink mediaSink;        

    private static final Logger logger = Logger.getLogger(AbstractSource.class);

    /**
     * Creates new instance of source with specified name.
     * 
     * @param name
     *            the name of the source to be created.
     */
    public AbstractSource(String name, PriorityQueueScheduler scheduler) {
        super(name);
        this.scheduler = scheduler;
        this.worker = new Worker(scheduler, 20000000); // 20ms in nanos
    }    

    /**
     * (Non Java-doc.)
     * 
     * @see org.mobicents.media.server.impl.AbstractSource#setInitialDelay(long) 
     */
    public void setInitialDelay(long initialDelay) {
        this.initialDelay = initialDelay;
    }

    
    /**
     * (Non Java-doc).
     * 
     * @see org.mobicents.media.MediaSource#getMediaTime();
     */
    public long getMediaTime() {
        return timestamp;
    }
    
    /**
     * (Non Java-doc).
     * 
     * @see org.mobicents.media.MediaSource#setDuration(long duration);
     */
    public void setDuration(long duration) {
        this.duration = duration;
    }
    
    /**
     * (Non Java-doc).
     * 
     * @see org.mobicents.media.MediaSource#getDuration();
     */
    public long getDuration() {
        return this.duration;
    }
    
    /**
     * (Non Java-doc).
     * 
     * @see org.mobicents.media.MediaSource#setMediaTime(long timestamp);
     */
    public void setMediaTime(long timestamp) {
        this.initialOffset = timestamp;
    }       

    /**
     * (Non Java-doc).
     * 
     * @see org.mobicents.media.MediaSource#start().
     */
    public void start() {
        if (!started.getAndSet(true)) {

            synchronized (worker) {
                //check scheduler
                try {

                    if (scheduler == null) {
                        throw new IllegalArgumentException("Scheduler is not assigned");
                    }

                    this.txBytes = 0;
                    this.txPackets = 0;

                    //reset media time and sequence number
                    timestamp = this.initialOffset;
                    this.initialOffset = 0;

                    sn = 0;



                    //just started component always synchronized as well
                    this.isSynchronized = true;

                    if (mediaSink != null)
                        mediaSink.start();

                    //scheduler worker
                    worker.resetMetronome();
                    scheduler.submitRT(worker, initialDelay);

                    //started!
                    started();
                } catch (Exception e) {
                    started.set(false);
                    failed(e);
                    logger.error(e);
                }
            }
        }
    }

    /**
     * Restores synchronization
     */
    public void wakeup() {

//        synchronized(worker) {
//            if (!started) {
//                return;
//            }
//
//            if (!this.isSynchronized) {
//                this.isSynchronized = true;
//                scheduler.submitRT(worker, initialDelay);
//            }
//        }
    }
    
    /**
     * (Non Java-doc).
     * 
     * @see org.mobicents.media.MediaSource#stop().
     */
    public void stop() {
        if (started.getAndSet(false)) {
            stopped();

            if (mediaSink != null) {
                mediaSink.stop();
            }

            timestamp = 0;
        }
    }

    public void activate()
    {
    	start();
    }
    
    public void deactivate()
    {
    	stop();
    }
    
    /**
     * (Non Java-doc).
     *
     */
    protected void connect(AbstractSink sink) {
        this.mediaSink = sink;

        if(started.get())
        	this.mediaSink.start();
    }

    /**
     * (Non Java-doc).
     *
     */
    protected void disconnect() {
    	if(this.mediaSink!=null)
    	{
    		this.mediaSink.stop();
    		this.mediaSink=null;
    	}
    }

    /**
     * (Non Java-doc).
     *
     */
    public boolean isConnected() {
        return mediaSink != null;
    }

    /**
     * (Non Java-doc).
     * 
     * @see org.mobicents.media.MediaSource#isStarted().
     */
    public boolean isStarted() {
        return this.started.get();
    }

    /**
     * This method must be overriden by concrete media source. T
     * he media have to fill buffer with media data and
     * attributes.
     *
     */
    public abstract Frame evolve(long timestamp);

    /**
     * Sends notification that media processing has been started.
     */
    protected void started() {
    }

    /**
     * Sends failure notification.
     * 
     * @param e the exception caused failure.
     */
    protected void failed(Exception e) {
    }

    /**
     * Sends notification that signal is completed.
     * 
     */
    protected void completed() {
        this.stop();
    }

    /**
     * Called when source is stopped by request
     * 
     */
    protected void stopped() {
    }

    /**
     * (Non Java-doc).
     *
     */
    public long getPacketsTransmitted() {
    	return txPackets;
    }

    /**
     * (Non Java-doc).
     *
     */
    public long getBytesTransmitted() {
        return txBytes;
    }

    @Override
    public void reset() {
        this.txPackets = 0;
        this.txBytes = 0;        
    }
    
    public String report() {
        return "";
    }    

    /**
     * Media generator task
     */
    private class Worker extends MetronomeTask {
    	/**
         * Creates new instance of task.
         *
         * @param scheduler the scheduler instance.
         */
    	//private long initialTime;
    	int length;
    	Frame frame;
    	long frameDuration;
    	Boolean isEOM;

    	
    	public Worker(PriorityQueueScheduler scheduler, long metronomeDelay) {
            super(scheduler, metronomeDelay);
        }
        




        @Override
        public void perform() {
            if (started.get()) {
                frame = evolve(timestamp);
                if (frame == null) {
                    // there was no frame generated. Try next cycle in at max 20 ms delay
                    next();
                } else {

                    //mark frame with media time and sequence number
                    frame.setTimestamp(timestamp);
                    frame.setSequenceNumber(sn);

                    //update media time and sequence number for the next frame
                    timestamp += frame.getDuration();
                    sn = (sn == Long.MAX_VALUE) ? 0 : sn + 1;

                    //set end_of_media flag if stream has reached the end
                    if (duration > 0 && timestamp >= duration) {
                        frame.setEOM(true);
                    }

                    frameDuration = frame.getDuration();
                    isEOM = frame.isEOM();
                    length = frame.getLength();

                    //delivering data to the other party.
                    if (mediaSink != null) {
                        long start = System.nanoTime();
                        mediaSink.perform(frame);
                        long diff = System.nanoTime() - start;
                        if (diff > 1000000) {
                            logger.warn("Perform took too long: " + diff + " , sink: " + mediaSink + ", source: " + AbstractSource.this);
                        }
                    }

                    //update transmission statistics
                    txPackets++;
                    txBytes += length;

                    //send notifications about media termination
                    //and do not resubmit this task again if stream has bee ended
                    if (isEOM) {
                        completed();
                    } else {
                        // reschedule for the next invocation
                        next();
                    }
                }

            }

        }

        @Override
        public String toString() {
            return AbstractSource.this.toString();
        }

    }
}
