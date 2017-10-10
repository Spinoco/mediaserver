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

package org.mobicents.media.server.component.oob;

import org.mobicents.media.ComponentType;
import org.mobicents.media.server.impl.AbstractSource;
import org.mobicents.media.server.scheduler.PriorityQueueScheduler;
import org.mobicents.media.server.spi.format.AudioFormat;
import org.mobicents.media.server.spi.format.FormatFactory;
import org.mobicents.media.server.spi.memory.Frame;
import org.mobicents.media.server.spi.memory.Memory;

/**
 *
 * @author yulian oifa
 */
public class OOBSender extends AbstractSource {
	
	private static final long serialVersionUID = 324088655270254479L;

	private final static AudioFormat dtmf = FormatFactory.createAudioFormat("telephone-event", 8000);
	private long period = 20000000L;
    private int packetSize = 4;
    
    private OOBInput input;
    private int currentIndex=0;
    
    public OOBSender(PriorityQueueScheduler scheduler) {
        super("oob.generator", scheduler);
        
        this.input=new OOBInput(ComponentType.SINE.getType());
        this.connect(this.input); 
    }

    public OOBInput getOOBInput()
    {
    	return this.input;
    }
    
    @Override
    public void activate() {
    	currentIndex=0;
    	super.activate();	
    }
    
    @Override
    public Frame evolve(long timestamp) {
    	if(currentIndex>=50)
    		return null;
    	
        Frame frame = Memory.allocate(packetSize);
        byte[] data = frame.getData();
        for (int i = 0; i < packetSize; i++)
            data[i++] = 0;        

        frame.setOffset(0);
        frame.setLength(packetSize);
        frame.setDuration(period);
        frame.setFormat(dtmf);
        
        currentIndex++;
        return frame;
    }
}