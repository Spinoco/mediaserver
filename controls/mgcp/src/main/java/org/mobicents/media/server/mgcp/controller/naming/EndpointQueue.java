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
package org.mobicents.media.server.mgcp.controller.naming;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.apache.logging.log4j.Logger;
import org.mobicents.media.server.mgcp.controller.MgcpEndpoint;
import org.mobicents.media.server.mgcp.controller.MgcpEndpointStateListener;
import org.mobicents.media.server.spi.EndpointInstaller;
import org.mobicents.media.server.utils.Text;


/**
 * Storage for endpoints of same type with search and reordering functions.
 * 
 * @author yulian oifa
 */
public class EndpointQueue implements MgcpEndpointStateListener {
    //reserved space for endpoint queue
    private final static int SIZE = 100;
    
    //wildcards
    private final static Text ANY = new Text("$");
    private final static Text ALL = new Text("*");
    
    //queue of endpoints
    private Map<Integer, MgcpEndpoint> completeList = new ConcurrentHashMap<>(SIZE);
    private ConcurrentLinkedQueue<MgcpEndpoint> queue = new ConcurrentLinkedQueue<MgcpEndpoint>();

    //reference for just found endpoind
    //private Holder holder;
    
    //index
    private int k;
    
    private EndpointInstaller installer;
    
    public  Logger logger = org.apache.logging.log4j.LogManager.getLogger(EndpointQueue.class);
    public void setInstaller(EndpointInstaller installer)
    {
    	this.installer=installer;
    }
    
    /**
     * Adds new endpoint to the queue.
     * 
     * @param endpoint the endpoint to be added
     */
    public void add(MgcpEndpoint endpoint, Integer idx) {
    	endpoint.setMgcpEndpointStateListener(this);
    	completeList.put(idx, endpoint);
        queue.offer(endpoint);
    }
    
    /**
     * Removes endpoint from the queue.
     * 
     * @param endpoint the endpoint to be removed.
     */
    public void remove(MgcpEndpoint endpoint) {
        String parts[] = endpoint.getName().split("/");
        String idx = parts[parts.length-1];
        completeList.remove(new Text(idx).toInteger());
    }
     
    /**
     * Finds endpoints matching to name pattern.
     * 
     * If "any" endpoint was requested, then first matching endpoint will be locked
     * and not available for search in future until this endpoint will be explicitly unlocked.
     * 
     * @param name the name pattern for search
     * @param endpoints collection which will be filled by found endpoints
     * @return the number of found endpoints.
     */
    public int find(Text name, MgcpEndpoint[] endpoints) {
    	//return all endpoint if all requested
        if (name.equals(ALL)) {
            k = 0;
            for (MgcpEndpoint endpoint: completeList.values())
                endpoints[k++] = endpoint;

            return completeList.size();
        }
        
        //return first free if ANY endpoint requested
        if (name.equals(ANY)) {        	        	
        	MgcpEndpoint endp=queue.poll();
        	while(endp==null && installer!=null && installer.canExpand())
        	{
          		logger.debug("No free endpoints,expanding");
            	
        		synchronized(installer)
        		{
        			installer.newEndpoint();
        		}
        		
        		endp=queue.poll();
        	}
        		
        	if(endp!=null) {
        		endp.lock();
        		endpoints[0] = endp;
        		if(logger.isDebugEnabled())    	
            	{
            		logger.debug("Endpoint " + endp.getName() + " taken (free="+queue.size()+")");
            	}
        		return 1;
        	}

            return 0;
        }
               
        int value=name.toInteger();
        if (completeList.containsKey(value)) {
            endpoints[0] = completeList.get(value);
            return 1;
        }

        return 0;
    }
    
    @Override
    public void onFreed(MgcpEndpoint endpoint)
    {
        remove(endpoint);
    	if(logger.isDebugEnabled())    	
    	{
    	    logger.debug("Endpoint " + endpoint.getName() + " released (free="+queue.size()+")");
    	}
    }
}
