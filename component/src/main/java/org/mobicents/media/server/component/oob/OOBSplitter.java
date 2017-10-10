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

import java.util.Iterator;
import java.util.concurrent.atomic.AtomicBoolean;

import org.mobicents.media.server.concurrent.ConcurrentMap;
import org.mobicents.media.server.scheduler.MetronomeTask;
import org.mobicents.media.server.scheduler.PriorityQueueScheduler;
import org.mobicents.media.server.scheduler.Task;
import org.mobicents.media.server.spi.memory.Frame;

/**
 * Implements compound oob splitter , one of core components of mms 3.0
 * 
 * @author Yulian Oifa
 * @author Henrique Rosa (henrique.rosa@telestax.com)
 */
public class OOBSplitter {

	// Mixing Scheduler
	private final PriorityQueueScheduler scheduler;

	// Components Pool
	private final ConcurrentMap<OOBComponent> insideComponents;
	private final ConcurrentMap<OOBComponent> outsideComponents;

	// Mixing Tasks
	private final InsideMixTask insideMixer;
	private final OutsideMixTask outsideMixer;
	private final AtomicBoolean started;

	protected long mixCount = 0;

	public OOBSplitter(PriorityQueueScheduler scheduler) {
		this.scheduler = scheduler;
		this.insideComponents = new ConcurrentMap<OOBComponent>();
		this.outsideComponents = new ConcurrentMap<OOBComponent>();
		this.insideMixer = new InsideMixTask(scheduler, 20000000);
		this.outsideMixer = new OutsideMixTask(scheduler, 20000000);
		this.started = new AtomicBoolean(false);
	}

	public void addInsideComponent(OOBComponent component) {
		insideComponents.put(component.getComponentId(), component);
	}

	public void addOutsideComponent(OOBComponent component) {
		outsideComponents.put(component.getComponentId(), component);
	}

	/**
	 * Releases inside component
	 * 
	 * @param component
	 */
	public void releaseInsideComponent(OOBComponent component) {
		insideComponents.remove(component.getComponentId());
	}

	/**
	 * Releases outside component
	 * 
	 * @param component
	 */
	public void releaseOutsideComponent(OOBComponent component) {
		outsideComponents.remove(component.getComponentId());
	}

	public void start() {
		if (! started.getAndSet(true)) {
			mixCount = 0;
			started.set(true);
			scheduler.submitRT(insideMixer, 0);
			scheduler.submitRT(outsideMixer, 0);
		}
	}

	public void stop() {
		if (started.getAndSet(false)) {
			started.set(false);
			insideMixer.cancel();
			outsideMixer.cancel();
		}
	}

	private class InsideMixTask extends MetronomeTask {


	    public InsideMixTask(PriorityQueueScheduler scheduler, long metronomeDelay) {
			super(scheduler, metronomeDelay);
		}


		@Override
		public long perform() {
			Frame current = null;

			// summarize all
			final Iterator<OOBComponent> insideRIterator = insideComponents.valuesIterator();
			while (insideRIterator.hasNext()) {
				OOBComponent component = insideRIterator.next();
				component.perform();
				current = component.getData();
				if (current != null) {
					break;
				}
			}

			if (current != null) {
				// frame is available, lets split it
				// get data for each component
				final Iterator<OOBComponent> outsideSIterator = outsideComponents.valuesIterator();
				while (outsideSIterator.hasNext()) {
					OOBComponent component = outsideSIterator.next();
					if (!outsideSIterator.hasNext()) {
						component.offer(current);
					} else {
						component.offer(current.clone());
					}
				}
			}


			next();
			mixCount++;


			return 0;
		}
	}

	private class OutsideMixTask extends MetronomeTask {

		public OutsideMixTask(PriorityQueueScheduler scheduler, long metronomeDelay) {
			super(scheduler, metronomeDelay);
		}

		@Override
		public long perform() {
			if (started.get()) {
				Frame current = null;

				// summarize all
				final Iterator<OOBComponent> outsideRIterator = outsideComponents.valuesIterator();
				while (outsideRIterator.hasNext()) {
					OOBComponent component = outsideRIterator.next();
					component.perform();
					current = component.getData();
					if (current != null) {
						break;
					}
				}

				if (current != null) {

					// get data for each component
					final Iterator<OOBComponent> insideSIterator = insideComponents.valuesIterator();
					while (insideSIterator.hasNext()) {
						OOBComponent component = insideSIterator.next();
						if (!insideSIterator.hasNext()) {
							component.offer(current);
						} else {
							component.offer(current.clone());
						}
					}

				}

				next();
				mixCount++;

			}

			return 0;
		}
	}
}
