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

import java.util.Iterator;
import java.util.concurrent.atomic.AtomicBoolean;

import org.mobicents.media.server.concurrent.ConcurrentMap;
import org.mobicents.media.server.scheduler.CancelableTask;
import org.mobicents.media.server.scheduler.EventQueueType;
import org.mobicents.media.server.scheduler.PriorityQueueScheduler;
import org.mobicents.media.server.scheduler.Task;
import org.mobicents.media.server.spi.format.AudioFormat;
import org.mobicents.media.server.spi.format.FormatFactory;

/**
 * Implements compound audio mixer , one of core components of mms 3.0
 *
 * @author Yulian Oifa
 */
public class AudioMixer {
	// scheduler for mixer job scheduling
	private PriorityQueueScheduler scheduler;

	// the format of the output stream.
	private AudioFormat format = FormatFactory.createAudioFormat("LINEAR", 8000, 16, 1);

	// The pool of components
	private ConcurrentMap<AudioComponent> components = new ConcurrentMap<AudioComponent>();

	private long period = 20000000L;
	private int packetSize = (int) (period / 1000000) * format.getSampleRate() / 1000 * format.getSampleSize() / 8;

	private MixTask mixer;
	private AtomicBoolean active = new AtomicBoolean(false);

	// gain value
	private double gain = 1.0;

	public AudioMixer(PriorityQueueScheduler scheduler) {
		this.scheduler = scheduler;
		this.mixer = new MixTask();
	}

	public void addComponent(AudioComponent component) {
		components.put(component.getComponentId(), component);
	}

	protected int getPacketSize() {
		return this.packetSize;
	}

	/**
	 * Releases unused input stream
	 *
	 * @param input
	 *            the input stream previously created
	 */
	public void release(AudioComponent component) {
		components.remove(component.getComponentId());
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
		if (!active.getAndSet(true)) {
			scheduler.submit(mixer, EventQueueType.RTP_MIXER);
		}
	}

	public void stop() {
	    active.set(false);
	}

	private class MixTask extends CancelableTask {
		int sourcesCount = 0;
		private int i;
		private int minValue = 0;
		private int maxValue = 0;
		private double currGain = 0;
		private int[] total = new int[packetSize / 2];
		private int[] current;

		MixTask() {
			super(active);
		}

		@Override
		public EventQueueType getQueueType() {
			return EventQueueType.RTP_MIXER;
		}

		@Override
		public String toString() {
			return "MixTask{" +
					"values: " + components.size() +
					"total:" + total.length +
					"}";
		}

		@Override
		public long perform() {
			// summarize all
			sourcesCount = 0;
			Iterator<AudioComponent> activeComponents = components.valuesIterator();
			while (activeComponents.hasNext()) {
				AudioComponent component = activeComponents.next();
				component.perform();
				current = component.getData();
				if (current != null) {
					if (sourcesCount == 0) {
						System.arraycopy(current, 0, total, 0, total.length);
					} else {
						for (i = 0; i < total.length; i++) {
							total[i] += current[i];
						}
					}
					sourcesCount++;
				}
			}

			if (sourcesCount == 0) {
			    int[] empty = AudioComponent.emptyData();

				activeComponents = components.valuesIterator();
				while (activeComponents.hasNext()) {
					AudioComponent component = activeComponents.next();
					component.offer(empty);
				}

				scheduler.submit(this,  EventQueueType.RTP_MIXER);
				return 0;
			}

			minValue = 0;
			maxValue = 0;
			for (i = 0; i < total.length; i++) {
				if (total[i] > maxValue) {
					maxValue = total[i];
				} else if (total[i] < minValue) {
					minValue = total[i];
				}
			}

			maxValue = Math.max(maxValue, Math.abs(minValue));

			currGain = gain;
			if (maxValue > Short.MAX_VALUE) {
				currGain = (currGain * (double) Short.MAX_VALUE) / (double) maxValue;
			}

			for (i = 0; i < total.length; i++) {
				total[i] = (short) ((double) total[i] * currGain);
			}

			// get data for each component
			activeComponents = components.valuesIterator();
			while (activeComponents.hasNext()) {
				AudioComponent component = activeComponents.next();
				current = component.getData();
				if (current == null) {
					component.offer(total);
				} else if (sourcesCount > 1) {
					for (i = 0; i < total.length; i++) {
						current[i] = total[i] - (short) ((double) current[i] * currGain);
					}
					component.offer(current);
				}
			}

			scheduler.submit(this,  EventQueueType.RTP_MIXER);
			return 0;
		}
	}
}
