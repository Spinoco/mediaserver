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

package org.mobicents.media.server.component.audio;

import org.mobicents.media.server.concurrent.ConcurrentMap;
import org.mobicents.media.server.scheduler.MetronomeTask;
import org.mobicents.media.server.scheduler.PriorityQueueScheduler;
import org.mobicents.media.server.spi.format.AudioFormat;
import org.mobicents.media.server.spi.format.FormatFactory;

import java.util.Iterator;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Implements compound audio splitter , one of core components of mms 3.0
 * 
 * @author Yulian Oifa
 * @author Henrique Rosa (henrique.rosa@telestax.com)
 */
public class AudioSplitter {

	// scheduler for mixer job scheduling
	private final PriorityQueueScheduler scheduler;

	// the format of the output stream.
	private static final AudioFormat FORMAT = FormatFactory.createAudioFormat("LINEAR", 8000, 16, 1);
	private static final long PERIOD = 20000000L;
	private static final int PACKET_SIZE = (int) (PERIOD / 1000000) * FORMAT.getSampleRate() / 1000 * FORMAT.getSampleSize() / 8;

	// The pools of components
	private final ConcurrentMap<AudioComponent> insideComponents;
	private final ConcurrentMap<AudioComponent> outsideComponents;

	private final InsideMixTask insideMixer;
	private final OutsideMixTask outsideMixer;
	private final AtomicBoolean started;
	private final AtomicLong mixCount;

	// gain value
	private double gain = 1.0;

	public AudioSplitter(PriorityQueueScheduler scheduler) {
		this.scheduler = scheduler;
		this.insideMixer = new InsideMixTask(scheduler, 20000000);
		this.outsideMixer = new OutsideMixTask(scheduler, 20000000);
		this.insideComponents = new ConcurrentMap<AudioComponent>();
		this.outsideComponents = new ConcurrentMap<AudioComponent>();
		this.started = new AtomicBoolean(false);
		this.mixCount = new AtomicLong(0);
	}

	public void addInsideComponent(AudioComponent component) {
		insideComponents.put(component.getComponentId(), component);
	}

	public void addOutsideComponent(AudioComponent component) {
		outsideComponents.put(component.getComponentId(), component);
	}

	protected int getPacketSize() {
		return PACKET_SIZE;
	}

	/**
	 * Releases inside component
	 * 
	 * @param component
	 */
	public void releaseInsideComponent(AudioComponent component) {
		insideComponents.remove(component.getComponentId());
	}

	/**
	 * Releases outside component
	 * 
	 * @param component
	 */
	public void releaseOutsideComponent(AudioComponent component) {
		outsideComponents.remove(component.getComponentId());
	}

	/**
	 * Modify gain of the output stream.
	 * 
	 * @param gain
	 *            the new value of the gain in dBm.
	 */
	public void setGain(double gain) {
		this.gain = gain > 0 ? gain * 1.26 : gain == 0 ? 1 : 1 / (gain * 1.26);
	}

	public void start() {
	    if(!this.started.getAndSet(true)) {
	        mixCount.set(0);
	        insideMixer.activateTask();
	        outsideMixer.activateTask();
	        scheduler.submitRT(insideMixer, 0);
	        scheduler.submitRT(outsideMixer, 0);
	    }
	}

	public void stop() {
	    if(this.started.getAndSet(false)) {
	        insideMixer.cancel();
	        outsideMixer.cancel();
	    }
	}

	private class InsideMixTask extends MetronomeTask {

	    private final int[] total = new int[PACKET_SIZE / 2];

		public InsideMixTask(PriorityQueueScheduler scheduler, long metronomeDelay) {
			super(scheduler, metronomeDelay);
		}


		@Override
		public long perform() {
			if (started.get()) {
				// summarize all
				boolean first = true;

				final Iterator<AudioComponent> insideRIterator = insideComponents.valuesIterator();
				while (insideRIterator.hasNext()) {
					AudioComponent component = insideRIterator.next();
					component.perform();
					int[] current = component.getData();
					if (current != null) {
						if (first) {
							System.arraycopy(current, 0, total, 0, total.length);
							first = false;
						} else {
							for (int i = 0; i < total.length; i++) {
								total[i] += current[i];
							}
						}
					}
				}

				if (! first) {


					int minValue = 0;
					int maxValue = 0;
					for (int i = 0; i < total.length; i++) {
						if (total[i] > maxValue) {
							maxValue = total[i];
						} else if (total[i] < minValue) {
							minValue = total[i];
						}
					}

					if (minValue > 0) {
						minValue = 0 - minValue;
					}

					if (minValue > maxValue) {
						maxValue = minValue;
					}

					double currGain = gain;
					if (maxValue > Short.MAX_VALUE) {
						currGain = (currGain * (double) Short.MAX_VALUE) / (double) maxValue;
					}

					for (int i = 0; i < total.length; i++) {
						total[i] = (short) Math.round((double) total[i] * currGain);
					}

					// get data for each component
					final Iterator<AudioComponent> outsideSIterator = outsideComponents.valuesIterator();
					while (outsideSIterator.hasNext()) {
						AudioComponent component = outsideSIterator.next();
						component.offer(total);
					}

				}

				next();
				mixCount.incrementAndGet();

			}

			return 0;
		}
	}

	private class OutsideMixTask extends MetronomeTask {
	    
		private final int[] total = new int[PACKET_SIZE / 2];

		public OutsideMixTask(PriorityQueueScheduler scheduler, long metronomeDelay) {
			super(scheduler, metronomeDelay);
		}


		@Override
		public String toString() {
			return "OutsideMixTask{" +
					"values: " + outsideComponents.size() +
					"total:" + total.length +
					"}";
		}

		@Override
		public long perform() {
			// summarize all
			boolean first = true;

			final Iterator<AudioComponent> outsideRIterator = outsideComponents.valuesIterator();
			while (outsideRIterator.hasNext()) {
				AudioComponent component = outsideRIterator.next();
				component.perform();
				int[] current = component.getData();
				if (current != null) {
					if (first) {
						System.arraycopy(current, 0, total, 0, total.length);
						first = false;
					} else {
						for (int i = 0; i < total.length; i++) {
							total[i] += current[i];
						}
					}
				}
			}

			if (!first) {


				int minValue = 0;
				int maxValue = 0;
				for (int i = 0; i < total.length; i++) {
					if (total[i] > maxValue) {
						maxValue = total[i];
					} else if (total[i] < minValue) {
						minValue = total[i];
					}
				}

				minValue = 0 - minValue;
				if (minValue > maxValue) {
					maxValue = minValue;
				}

				double currGain = gain;
				if (maxValue > Short.MAX_VALUE) {
					currGain = (currGain * Short.MAX_VALUE) / maxValue;
				}

				for (int i = 0; i < total.length; i++) {
					total[i] = (short) Math.round((double) total[i] * currGain);
				}

				// get data for each component
				final Iterator<AudioComponent> insideSIterator = insideComponents.valuesIterator();
				while (insideSIterator.hasNext()) {
					AudioComponent component = insideSIterator.next();
					component.offer(total);
				}

			}

			next();
			mixCount.incrementAndGet();

			return 0;
		}
	}
}
