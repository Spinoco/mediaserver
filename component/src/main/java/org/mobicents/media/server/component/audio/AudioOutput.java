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

import org.mobicents.media.server.impl.AbstractSink;
import org.mobicents.media.server.impl.AbstractSource;
import org.mobicents.media.server.scheduler.EventQueueType;
import org.mobicents.media.server.scheduler.PriorityQueueScheduler;
import org.mobicents.media.server.spi.memory.Frame;

import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Implements output for compound components.
 * 
 * @author Yulian Oifa
 */
public class AudioOutput extends AbstractSource {

	private static final long serialVersionUID = -5988244809612104056L;

	private int outputId;
	private ConcurrentLinkedQueue<Frame> buffer = new ConcurrentLinkedQueue<Frame>();

	/**
	 * Creates new instance with default name.
	 */
	public AudioOutput(PriorityQueueScheduler scheduler, int outputId) {
		super("compound.output(AudioOutput)", scheduler, EventQueueType.RTP_MIXER);
		this.outputId = outputId;
	}

	public int getOutputId() {
		return outputId;
	}

	public void join(AbstractSink sink) {
		connect(sink);
	}

	public void unjoin() {
		disconnect();
	}

	@Override
	public String toString() {
		return "AudioOutput{" +
				"name=" + getName() +
				"outputId=" + outputId +
				", bufferSize=" + buffer.size() +
				", sink="+ ((mediaSink!=null) ? mediaSink : "null") +
				'}';
	}

	@Override
	public Frame evolve(long timestamp) {
		return buffer.poll();
	}

	@Override
	public void stop() {
		buffer = new ConcurrentLinkedQueue<>();
		super.stop();
	}

	public void offer(Frame frame) {
		if (buffer.size() > 1) {
			buffer.poll();
		}
		buffer.offer(frame);
	}
}
