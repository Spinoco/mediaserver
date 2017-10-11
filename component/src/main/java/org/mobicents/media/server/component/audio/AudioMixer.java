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
	private AtomicBoolean started = new AtomicBoolean(false);

	public long mixCount = 0;

	// gain value
	private double gain = 1.0;

	public AudioMixer(PriorityQueueScheduler scheduler) {
		this.scheduler = scheduler;
		this.mixer = new MixTask(scheduler, 20000000);
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
		if (!started.getAndSet(true)) {
			mixCount = 0;
			mixer.resetMetronome();
			scheduler.submitRT(mixer, 0);
		}
	}

	public void stop() {
		 started.set(false);
	}

	private class MixTask extends MetronomeTask {
		int sourcesCount = 0;
		private int i;
		private int minValue = 0;
		private int maxValue = 0;
		private double currGain = 0;
		private int[] total = new int[packetSize / 2];
		private int[] current;

		public MixTask(PriorityQueueScheduler scheduler, long metronomeDelay) {
			super(scheduler, metronomeDelay);
		}


		@Override
		public String toString() {
			return "MixTask{" +
					"values: " + components.size() +
					"total:" + total.length +
					"}";
		}

		@Override
		public void perform() {
			if (started.get()) {
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

				if (sourcesCount != 0) {

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
				}

				next();
				mixCount++;

			}
		}
	}
}
