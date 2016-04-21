/*
 * TeleStax, Open Source Cloud Communications
 * Copyright 2011-2016, Telestax Inc and individual contributors
 * by the @authors tag. 
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

package org.mobicents.media.core.connections;

import org.junit.Test;
import org.mobicents.media.server.component.DspFactoryImpl;
import org.mobicents.media.server.impl.rtp.ChannelsManager;
import org.mobicents.media.server.io.network.UdpManager;
import org.mobicents.media.server.scheduler.Clock;
import org.mobicents.media.server.scheduler.PriorityQueueScheduler;
import org.mobicents.media.server.scheduler.Scheduler;
import org.mobicents.media.server.scheduler.ServiceScheduler;
import org.mobicents.media.server.scheduler.WallClock;
import org.mobicents.media.server.spi.dsp.DspFactory;

import junit.framework.Assert;

/**
 * @author Henrique Rosa (henrique.rosa@telestax.com)
 *
 */
public class RtpConnectionPoolTest {
    
    private final Clock clock;
    private final PriorityQueueScheduler mediaScheduler;
    private final Scheduler taskScheduler;
    private final UdpManager udpManager;
    private final ChannelsManager connectionFactory;
    private final DspFactory dspFactory;
    
    public RtpConnectionPoolTest() {
        this.clock = new WallClock();
        this.mediaScheduler = new PriorityQueueScheduler();
        this.taskScheduler = new ServiceScheduler(clock);
        this.udpManager = new UdpManager(taskScheduler);
        this.connectionFactory = new ChannelsManager(udpManager);
        this.dspFactory = new DspFactoryImpl();
        
        this.mediaScheduler.setClock(clock);
        this.connectionFactory.setScheduler(mediaScheduler);
    }

    @Test
    public void testConnectionRecycle() {
        // given
        RtpConnectionFactory factory = new RtpConnectionFactory(connectionFactory, dspFactory);
        RtpConnectionPool pool = new RtpConnectionPool(1, factory);
        
        // when
        RtpConnectionImpl connection = pool.poll();
        
        String oldCname = connection.getCname();
        int oldId = connection.getId();
        
        pool.offer(connection);
        connection = pool.poll();
        
        // then
        Assert.assertTrue(pool.isEmpty());
        Assert.assertTrue(!oldCname.equals(connection.getCname()));
        Assert.assertEquals(oldId, connection.getId());
    }
    
}
