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
package org.mobicents.media.server.mgcp.tx;

import org.mobicents.media.server.scheduler.*;

import java.util.Random;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 *
 * @author yulian oifa
 */
public class TransactionManagerTest {
    
    private Clock clock;
    private Scheduler scheduler;
    private TransactionManager txManager;
    private Action action;
    
    @Before
    public void setUp() {
        clock = new WallClock();
        scheduler = new ServiceScheduler();
        scheduler.start();
        
        txManager = new TransactionManager(clock, scheduler, 3);
        txManager.start();
        
        action = new Action();
        
        TaskChain actionHandler = new TaskChain(2,scheduler);
        actionHandler.add(new MyTask());
        actionHandler.add(new MyTask());
        
        action.setActionHandler(actionHandler);
    }
    
    @After
    public void tearDown() {
        scheduler.stop();
    }

    /**
     * Test of find method, of class TransactionManager.
     */
    @Test
    public void testFind() {
        Transaction tx = txManager.allocateNew(1);
        assertTrue("Transaction not found", tx != null);

        tx = txManager.allocateNew(2);
        assertTrue("Transaction not found", tx != null);
        
        tx = txManager.allocateNew(3);
        assertTrue("Transaction not found", tx != null);
        
        tx = txManager.allocateNew(4);
        assertTrue("Transaction still in pool", tx != null);
    }
    
    @Test
    public void testTermination() {
        Transaction tx = txManager.allocateNew(1);
        assertTrue("Transaction not found", tx != null);        
    }

	private class MyTask extends Task {
		private Random rnd = new Random();

		public MyTask() {
			super();
		}

		public EventQueueType getQueueType() {
			return EventQueueType.MGCP_SIGNALLING;
		}

		@Override
		public long perform() {
			boolean flag = rnd.nextBoolean();
			System.out.println("TXID=" + action.transaction().getId());
			if (flag) {
				throw new IllegalStateException();
			}

			return 0;
		}

	}
}
