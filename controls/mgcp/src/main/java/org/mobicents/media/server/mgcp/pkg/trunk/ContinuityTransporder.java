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

package org.mobicents.media.server.mgcp.pkg.trunk;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.log4j.Logger;
import org.mobicents.media.ComponentType;
import org.mobicents.media.server.impl.resource.phone.PhoneSignalDetector;
import org.mobicents.media.server.impl.resource.phone.PhoneSignalGenerator;
import org.mobicents.media.server.mgcp.controller.signal.Event;
import org.mobicents.media.server.mgcp.controller.signal.NotifyImmediately;
import org.mobicents.media.server.mgcp.controller.signal.Signal;
import org.mobicents.media.server.scheduler.PriorityQueueScheduler;
import org.mobicents.media.server.scheduler.Task;
import org.mobicents.media.server.spi.MediaType;
import org.mobicents.media.server.spi.tone.ToneDetectorListener;
import org.mobicents.media.server.spi.tone.ToneEvent;
import org.mobicents.media.server.utils.Text;
/**
 * Implements continuity transporder signal.
 * 
 * @author Oifa Yulian
 */
public class ContinuityTransporder extends Signal implements ToneDetectorListener {    
	private Event of = new Event(new Text("of"));
	private Event oc = new Event(new Text("oc"));
	
    private volatile Options options;
    
    private PhoneSignalGenerator phoneGenerator;
    private PhoneSignalDetector phoneDetector;
    
    private final static Logger logger = Logger.getLogger(ContinuityTransporder.class);    
    private Heartbeat heartbeat;
    
    public static final Text[] toneOptions={new Text("co1"),new Text("co2")};
    public static final int[] toneValues={2010,1780};
    
    private PriorityQueueScheduler scheduler;
	
    private Boolean hasReceivingTone=false;
	
    public ContinuityTransporder(String name) {
        super(name);         
        of.add(new NotifyImmediately("N"));
        oc.add(new NotifyImmediately("N"));        
    }
    
    @Override
    public void execute() {
    	//get access to input and output
    	phoneGenerator = this.getPhoneGenerator();
        phoneDetector = this.getPhoneDetector();
        
        //check result
        if (phoneGenerator == null || phoneDetector ==null) {
            of.fire(this, new Text("Endpoint is not ss7 endpoint"));
            complete();
            return;
        }
        
        //get options of the request
        options = new Options(getTrigger().getParams());        
                
        if(options.isDeactivation())
        {
        	//deactivate pipe
        	endToneReceiving();
        }
        else
        {
        	if(heartbeat==null)
        	{
        		this.scheduler=getEndpoint().getScheduler();
        		heartbeat=new Heartbeat(this);
        	}
        	
        	prepareToneReceiving();
        }        	       
        
        //signal does not have anything else , only looping ss7 channel
    }
    
    @Override
    public boolean doAccept(Text event) {
        if (!of.isActive() && of.matches(event)) {
            return true;
        }
        
        if (!oc.isActive() && oc.matches(event)) {
            return true;
        }
        
        return false;
    }

    @Override
    public void reset() {
        super.reset();
        
        endToneReceiving();
    	
    	if(heartbeat!=null)
    		heartbeat.disable();
        
        oc.reset();
        of.reset();        
    }
    
    @Override
    public void cancel() {    
    	//deactivate pipe
    	endToneReceiving();
    	
    	if(heartbeat!=null)
    		heartbeat.disable();  	
    }
    
    private PhoneSignalGenerator getPhoneGenerator() {
    	return (PhoneSignalGenerator) getEndpoint().getResource(MediaType.AUDIO, ComponentType.SIGNAL_GENERATOR); 
    }
    
    private PhoneSignalDetector getPhoneDetector() {
    	return (PhoneSignalDetector) getEndpoint().getResource(MediaType.AUDIO, ComponentType.SIGNAL_DETECTOR); 
    }
    
    private void prepareToneReceiving()
    {
    	Boolean found=false;
    	for(int i=0;i<toneOptions.length;i++)
    		if(toneOptions[i].equals(options.getInTone()))
    		{
    			found=true;
    			phoneDetector.setFrequency(new int[] {toneValues[i]});
    			phoneDetector.activate();
    			break;
    		}    	
    	        
    	for(int i=0;i<toneOptions.length;i++)
    		if(toneOptions[i].equals(options.getOutTone()))
    		{
    			hasReceivingTone=true;
    			phoneGenerator.setFrequency(new int[] {toneValues[i]});
    			break;
    		}
    	
    	if(!found) {
            of.fire(this, new Text("t/co1"));
            complete();
            return;
        } 
    	else
    	{
    		//set ttl to 2 seconds
        	heartbeat.setTtl((int)(20));
        	heartbeat.activate();
    		getEndpoint().getScheduler().submitHeartbeat(heartbeat);
    		
        	try
        	{
        		//set itself as listener
        		phoneDetector.addListener(this);
        	}
        	catch(Exception ex)
        	{
        		logger.error("OPERATION FAILURE", ex);
        	}
    	}
    }    
    
    private void endToneReceiving()
    {
    	if(phoneDetector!=null)
    	{
    		phoneDetector.removeListener(this);
    		phoneDetector.deactivate();
    		phoneDetector=null;
    	}
    	
    	if(phoneGenerator!=null)
    	{
    		phoneGenerator.deactivate();
    		phoneGenerator=null;
    	}
    }
    
    @Override
    public void process(ToneEvent event) {
    	if(phoneDetector!=null)
    	{
    		phoneDetector.removeListener(this);
    		phoneDetector.deactivate(); 
    		phoneDetector=null;    		
    	}
    	
    	heartbeat.disable();
    	
    	if(!hasReceivingTone) {
            of.fire(this, new Text("t/co2"));
            complete();
            return;
        }
    	
    	phoneGenerator.activate();		
    }
    
    private class Heartbeat extends Task {
    	private AtomicInteger ttl;
    	private AtomicBoolean active;
    	
    	private Signal signal;
    	
    	public Heartbeat(Signal signal) {
        	super();
        	
        	ttl=new AtomicInteger(-1);
        	active=new AtomicBoolean(false);
            this.signal=signal;
        }

        
        public void setTtl(int value)
        {
        	ttl.set(value);        	        
        }
        
        public void disable()
        {
        	this.active.set(false);        	
        }
        
        public void activate()
        {
        	this.active.set(true);  	
        }
        
        @Override
        public long perform() {        	
        	if(!active.get())
        		return 0;
        	
        	int ttlValue=ttl.get();
        	
        	if(ttlValue!=0)
        	{
        		if(ttlValue>0)
        			ttl.set(ttlValue-1);
        		
        		scheduler.submitHeartbeat(this);
        		return 0;
        	}
        	
        	logger.info(String.format("(%s) Timeout expired waiting for tone", getEndpoint().getLocalName()));
        	endToneReceiving();
        	oc.fire(signal, options.getInTone());  
        	complete();
        	this.disable();
        	return 0;
        }
    }
}
