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

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

import org.mobicents.media.ComponentType;
import org.mobicents.media.server.component.audio.AudioOutput;
import org.mobicents.media.server.component.oob.OOBOutput;
import org.mobicents.media.server.impl.AbstractSink;
import org.mobicents.media.server.scheduler.PriorityQueueScheduler;
import org.mobicents.media.server.scheduler.Task;
import org.mobicents.media.server.spi.dtmf.DtmfTonesData;
import org.mobicents.media.server.spi.format.AudioFormat;
import org.mobicents.media.server.spi.format.FormatFactory;
import org.mobicents.media.server.spi.format.Formats;
import org.mobicents.media.server.spi.listener.Listeners;
import org.mobicents.media.server.spi.listener.TooManyListenersException;
import org.mobicents.media.server.spi.memory.Frame;
import org.mobicents.media.server.spi.recorder.Recorder;
import org.mobicents.media.server.spi.recorder.RecorderEvent;
import org.mobicents.media.server.spi.recorder.RecorderListener;

import org.apache.log4j.Logger;

/**
 *
 * @author yulian oifa
 */
public class AudioRecorderImpl extends AbstractSink implements Recorder {

    private final static AudioFormat LINEAR = FormatFactory.createAudioFormat("linear", 8000, 16, 1);
    private final static Formats formats = new Formats();
    
    private long period = 20000000L;
    private int packetSize = (int)(period / 1000000) * LINEAR.getSampleRate()/1000 * LINEAR.getSampleSize() / 8;
    
    private final static int SILENCE_LEVEL = 10;
    
    static {
        formats.add(LINEAR);
    }
    
    private String recordDir;
    private FileOutputStream fout;    
    //file for recording
    private File file;
    
    //temp file for raw data
    private File temp;
    
    //if set ti true the record will terminate recording when silence detected
    private long postSpeechTimer = -1L;
    private long preSpeechTimer = -1L;
    
    //samples
    private ByteBuffer byteBuffer=ByteBuffer.allocateDirect(8192);
    private ByteBuffer headerBuffer=ByteBuffer.allocateDirect(44);
    private byte[] data;
    private int offset;
    private int len;
    
    private KillRecording killRecording;
    private Heartbeat heartbeat;
    
    private long lastPacketData=0,startTime=0;
    
    private PriorityQueueScheduler scheduler;
    
    //maximum recrding time. -1 means until stopped.
    private long maxRecordTime = -1;
    
    //listener
    private Listeners<RecorderListener> listeners = new Listeners<RecorderListener>();
    
    //events
    private RecorderEventImpl recorderStarted;
    private RecorderEventImpl recorderStopped;
    private RecorderEventImpl recorderFailed;
    
    //event sender task
    private EventSender eventSender;
    
    //event qualifier
    private int qualifier;
    
    private boolean speechDetected=false;
    
    private AudioOutput output;
    private OOBOutput oobOutput;
    private OOBRecorder oobRecorder;
    
    private static final Logger logger = Logger.getLogger(AudioRecorderImpl.class);
    
    public AudioRecorderImpl(PriorityQueueScheduler scheduler) {
        super("recorder");
        this.scheduler = scheduler;
        
        
        killRecording = new KillRecording();
        
        //initialize events
        recorderStarted = new RecorderEventImpl(RecorderEvent.START, this);
        recorderStopped = new RecorderEventImpl(RecorderEvent.STOP, this);
        recorderFailed = new RecorderEventImpl(RecorderEvent.FAILED, this);
        
        //initialize event sender task
        eventSender = new EventSender();
        heartbeat = new Heartbeat();
        
        output=new AudioOutput(scheduler,ComponentType.RECORDER.getType());
        output.join(this);
        
        oobOutput=new OOBOutput(scheduler,ComponentType.RECORDER.getType());
        oobRecorder=new OOBRecorder();
        oobOutput.join(oobRecorder);
    }

    public AudioOutput getAudioOutput()
    {
    	return this.output;
    }
    
    public OOBOutput getOOBOutput()
    {
    	return this.oobOutput;
    }
    
    @Override
    public void activate() {
    	this.lastPacketData=scheduler.getClock().getTime();
    	this.startTime=scheduler.getClock().getTime();
    	
    	output.start();
    	oobOutput.start();
        if(this.postSpeechTimer>0 || this.preSpeechTimer>0 || this.maxRecordTime>0)
        	scheduler.submitHeatbeat(this.heartbeat);
        
        //send event
        fireEvent(recorderStarted);
    }
    
    @Override
    public void deactivate() {
        if (!this.isStarted()) {
            return;
        }
        
        output.stop();
        oobOutput.stop();
        this.maxRecordTime = -1;
        this.lastPacketData=0;
        this.startTime=0;
        
        this.heartbeat.cancel();
        
        try {
            writeToWaveFile();
        } catch (IOException e) {
        	if(getEndpoint()==null)
        		logger.error(e);
        	else
        		logger.error("(" + getEndpoint().getLocalName() + ")",e);        		
        }
        
        //send event
        recorderStopped.setQualifier(qualifier);
        fireEvent(recorderStopped);
        
        //clean qualifier
        this.qualifier = 0;
        this.maxRecordTime = -1L;
        this.postSpeechTimer = -1L;
        this.preSpeechTimer = -1L;
        this.speechDetected=false;       
    }
    
    /**
     * (Non Java-doc.)
     * 
     * @see org.mobicents.media.server.spi.recorder.Recorder;
     */
    public void setPreSpeechTimer(long value) {
        this.preSpeechTimer = value;
    }
    
    /**
     * (Non Java-doc.)
     * 
     * @see org.mobicents.media.server.spi.recorder.Recorder;
     */
    public void setPostSpeechTimer(long value) {
        this.postSpeechTimer = value;
    }
    
    /**
     * (Non Java-doc.)
     * 
     * @see org.mobicents.media.server.spi.recorder.Recorder;
     */
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
        scheduler.submit(eventSender,scheduler.INPUT_QUEUE);
    }
    
    @Override
    public void onMediaTransfer(Frame frame) throws IOException {
        //extract data
        data = frame.getData();
        offset = frame.getOffset();
        len = frame.getLength();
        
        byteBuffer.clear();
        byteBuffer.limit(len-offset);
        byteBuffer.put(data, offset, len-offset);
        byteBuffer.rewind();
        fout.getChannel().write(byteBuffer);
        
        if (this.postSpeechTimer > 0 || this.preSpeechTimer>0) {
            //detecting silence
            if (!this.checkForSilence(data, offset, len)) {
                this.lastPacketData=scheduler.getClock().getTime();
                this.speechDetected=true;
            }
        }
        else
        	this.lastPacketData=scheduler.getClock().getTime();                
    }

    public void setRecordDir(String recordDir) {
        this.recordDir = recordDir;
    }

    public void setRecordFile(String uri, boolean append) throws IOException {
        //calculate the full path
        String path = uri.startsWith("file:")  ?  uri.replaceAll("file://", "") :
                this.recordDir + "/" + uri;
        
        //create file for recording and temp file
        file = new File(path);        
        temp = new File(path + "~");                
        
        //open stream to temporary file
        fout = new FileOutputStream(temp);
        
        //if append specified and file really exist copy data from the current
        //file to temp
        if (append && file.exists()) {
        	if(getEndpoint()==null)
        		logger.info("..............>>>>>Copying samples from " + file);
        	else
        		logger.info("(" + getEndpoint().getLocalName() + ") ..............>>>>>Copying samples from " + file);        		
            copySamples(file, fout);                       
        }
    }
    
    /**
     * Writes samples to file following WAVE format.
     * 
     * @throws IOException 
     */
    private void writeToWaveFile() throws IOException {
    	if(getEndpoint()==null)
    		logger.info("!!!!!!!!!! Writting to file......................");
    	else
    		logger.info("(" + getEndpoint().getLocalName() + ") !!!!!!!!!! Writting to file......................");
    	
        //stop called on inactive recorder
        if (fout == null) {
            return;
        }
        
        fout.flush();
        fout.close();
                
        FileInputStream fin = new FileInputStream(temp);
        fout = new FileOutputStream(file);
        
        int size = fin.available();
        if(getEndpoint()==null)
        	logger.info("!!!!!!!!!! Size=" + size);
        else
        	logger.info("(" + getEndpoint().getLocalName() + ") !!!!!!!!!! Size=" + size);
        
		headerBuffer.clear();		
        //RIFF
		headerBuffer.put((byte) 0x52);
		headerBuffer.put((byte) 0x49);
		headerBuffer.put((byte) 0x46);
		headerBuffer.put((byte) 0x46);
        
        int length = size + 36;
        
        //Length
        headerBuffer.put((byte) (length));
        headerBuffer.put((byte) (length >> 8));
        headerBuffer.put((byte) (length >> 16));
        headerBuffer.put((byte) (length >> 24));
        
        //WAVE
        headerBuffer.put((byte) 0x57);
        headerBuffer.put((byte) 0x41);
        headerBuffer.put((byte) 0x56);
        headerBuffer.put((byte) 0x45);
        
        //fmt
        headerBuffer.put((byte) 0x66);
        headerBuffer.put((byte) 0x6d);
        headerBuffer.put((byte) 0x74);
        headerBuffer.put((byte) 0x20);
        
        headerBuffer.put((byte) 0x10);
        headerBuffer.put((byte) 0x00);
        headerBuffer.put((byte) 0x00);
        headerBuffer.put((byte) 0x00);
        
        //format - PCM
        headerBuffer.put((byte) 0x01);
        headerBuffer.put((byte) 0x00);
        
        //format - MONO
        headerBuffer.put((byte) 0x01);
        headerBuffer.put((byte) 0x00);
        
        //sample rate:8000
        headerBuffer.put((byte) 0x40);
        headerBuffer.put((byte) 0x1F);
        headerBuffer.put((byte) 0x00);
        headerBuffer.put((byte) 0x00);

        //byte rate
        headerBuffer.put((byte) 0x80);
        headerBuffer.put((byte) 0x3E);
        headerBuffer.put((byte) 0x00);
        headerBuffer.put((byte) 0x00);
        
        
        //Block align
        headerBuffer.put((byte) 0x02);
        headerBuffer.put((byte) 0x00);
        
        //Bits per sample: 16
        headerBuffer.put((byte) 0x10);
        headerBuffer.put((byte) 0x00);
        
        //"data"
        headerBuffer.put((byte) 0x64);
        headerBuffer.put((byte) 0x61);
        headerBuffer.put((byte) 0x74);
        headerBuffer.put((byte) 0x61);
        
        //len
        headerBuffer.put((byte) (size));
        headerBuffer.put((byte) (size >> 8));
        headerBuffer.put((byte) (size >> 16));
        headerBuffer.put((byte) (size >> 24));
        
        headerBuffer.rewind();
        
        //lets write header
        FileChannel outChannel=fout.getChannel();        
        outChannel.write(headerBuffer);
        outChannel.force(true);
        
        //lets write data
        FileChannel inChannel=fin.getChannel();
        outChannel.transferFrom(fin.getChannel(), 44, inChannel.size());
        if(getEndpoint()==null)
        	logger.info("Was copied " + inChannel.size()  + " bytes");
        else
        	logger.info("(" + getEndpoint().getLocalName() + ") Was copied " + inChannel.size()  + " bytes");        
        
        
        fout.flush();
        fout.close();
        
        fin.close();
        temp.delete();              
    }
    
    /**
     * Copies samples from source wav file to temporary raw destination.
     * 
     * @param src wav source file
     * @param dst raw destination file.
     */
    private void copySamples(File src, FileOutputStream out) throws IOException {
        FileInputStream in = new FileInputStream(src);
        FileChannel inChannel = in.getChannel();
    	FileChannel outChannel = out.getChannel();    	
    	
        try {
            this.copyData(inChannel, 44, outChannel);
        } finally {
            in.close();
        }
     }
    
    /**
     * Copies data from specified input to specified destination.
     * 
     * @param in the input of data
     * @param offset the first position of data to read
     * @param out destination
     * @throws IOException 
     */
    private void copyData(FileChannel inChannel, int offset,  FileChannel outChannel) throws IOException {
    	long count=inChannel.size()-(long)offset;
    	inChannel.transferTo(offset,count,outChannel);    	
                        
    	if(getEndpoint()==null)
        	logger.info("Was copied " + count  + " bytes");
        else
        	logger.info("(" + getEndpoint().getLocalName() + ") Was copied " + count + " bytes");                    	        
    }
    
    /**
     * Checks does the frame contains sound or silence.
     * 
     * @param data  buffer with samples
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

    /**
     * (Non Java-doc.)
     * 
     * @see org.mobicents.media.server.spi.recorder.Recorder#addListener(org.mobicents.media.server.spi.recorder.RecorderListener) 
     */
    public void addListener(RecorderListener listener) throws TooManyListenersException {
        listeners.add(listener);
    }

    /**
     * (Non Java-doc.)
     * 
     * @see org.mobicents.media.server.spi.recorder.Recorder#removeListener(org.mobicents.media.server.spi.recorder.RecorderListener) 
     */
    public void removeListener(RecorderListener listener) {
        listeners.remove(listener);
    }
    
    public void clearAllListeners() {
    	listeners.clear();
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
    
        public int getQueueNumber() {
            return scheduler.INPUT_QUEUE;
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
    
        public int getQueueNumber() {
            return scheduler.INPUT_QUEUE;
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
        	long currTime=scheduler.getClock().getTime();        	
        	
        	if(preSpeechTimer>0 && !speechDetected && currTime-lastPacketData>preSpeechTimer) {
        		qualifier = RecorderEvent.NO_SPEECH;                    
                scheduler.submit(killRecording,scheduler.INPUT_QUEUE);
                return 0;
        	}
        	
        	if(postSpeechTimer>0 && speechDetected && currTime-lastPacketData>postSpeechTimer) {
        	    qualifier = RecorderEvent.NO_SPEECH;                    
                scheduler.submit(killRecording,scheduler.INPUT_QUEUE);
                return 0;
            }        	
        		
        	//check max time and stop recording if exeeds limit
            if (maxRecordTime > 0 && currTime-startTime >= maxRecordTime) {
                //set qualifier
                qualifier = RecorderEvent.MAX_DURATION_EXCEEDED;            
                scheduler.submit(killRecording,scheduler.INPUT_QUEUE);
                return 0;
            }
            
            scheduler.submitHeatbeat(this);
            return 0;
        }
    
        public int getQueueNumber() {
            return scheduler.HEARTBEAT_QUEUE;
        }
    }
    
    private class OOBRecorder extends AbstractSink
    {
    	 private byte currTone=(byte)0xFF;
    	 private long latestSeq=0;
    	 
    	 private boolean hasEndOfEvent=false;
    	 private long endSeq=0;
    	 
    	 private ByteBuffer toneBuffer=ByteBuffer.allocateDirect(1600);
    	    
    	 byte[] data = new byte[4];
    	    
    	 public OOBRecorder()
    	 {
    		super("oob recorder");
    	 }
    	
    	 public void onMediaTransfer(Frame buffer) throws IOException {
    		byte[] data=buffer.getData();
    		if(data.length!=4)
            	return;
        	
        	boolean endOfEvent=false;
            endOfEvent=(data[1] & 0X80)!=0;
            
           //lets ignore end of event packets
            if(endOfEvent)
            {
            	hasEndOfEvent=true;
            	endSeq=buffer.getSequenceNumber();
            	return;                                       
            }
            
            //lets update sync data , allowing same tone come after 160ms from previous tone , not including end of tone
            if(currTone==data[0])
            {
            	if(hasEndOfEvent)
            	{
            		if(buffer.getSequenceNumber()<=endSeq && buffer.getSequenceNumber()>(endSeq-8))
            			//out of order , belongs to same event 
            			//if comes after end of event then its new one
            			return;
            	}
            	else if((buffer.getSequenceNumber()<(latestSeq+8)) && buffer.getSequenceNumber()>(latestSeq-8))
            	{
            		if(buffer.getSequenceNumber()>latestSeq)
            			latestSeq=buffer.getSequenceNumber();            			
            		
                    return;
            	}
            }
            
            hasEndOfEvent=false;
        	endSeq=0;
        	
        	latestSeq=buffer.getSequenceNumber();
            currTone=data[0];
            toneBuffer.clear();
        	toneBuffer.limit(DtmfTonesData.buffer[data[0]].length);        	
        	toneBuffer.put(DtmfTonesData.buffer[data[0]]);            
            toneBuffer.rewind();            
            fout.getChannel().write(toneBuffer);            
    	 }
    	
    	 public void activate()
    	 {
    	 }
    	
    	 public void deactivate()
    	 {        
    	 }
    }    
}
