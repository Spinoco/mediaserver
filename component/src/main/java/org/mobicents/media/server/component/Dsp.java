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

package org.mobicents.media.server.component;

import org.mobicents.media.server.spi.dsp.Codec;
import org.mobicents.media.server.spi.dsp.Processor;
import org.mobicents.media.server.spi.format.Format;
import org.mobicents.media.server.spi.memory.Frame;

/**
 * Digital signaling processor.
 *
 * DSP transforms media from its original format to one of the specified
 * output format. Output formats are specified as array where order of the
 * formats defines format's priority. If frame has format matching to output
 * format the frame won't be changed.
 *
 * @author kulikov
 */
public class Dsp implements Processor {
    private Codec codec;
    private Codec[] codecs;

    //The current format of the frame stream
    private Format sourceFormat,destinationFormat;

    /**
     * Creates new instance of processor.
     *
     * @param codecs
     */
    protected Dsp(Codec[] codecs) {
        this.codecs = codecs;
    }

    @Override
    public Codec[] getCodecs() {
        return codecs;
    }

    private Frame processFrame(Frame frame, Codec codec) {
    	try {
    		return codec.process(frame);
		} finally {
    		frame.recycle();
		}
	}

    @Override
    public Frame process(Frame frame, Format source, Format destination) {
    	if (source == null || destination == null || source.matches(destination))
			return frame;

    	//normal flow: format of the stream is already known
		if (codec != null && source.matches(sourceFormat) && destination.matches(destinationFormat)) {
			return processFrame(frame, codec);
		}

		//check that codecs are defined.
		if (codecs == null) {
			//no spade - no questions
			return frame;
		}

		for (int i = 0; i < codecs.length; i++) {
			//select codec which can receive current frame && can transform frame to any of the output format
			if (codecs[i].getSupportedInputFormat().matches(source)
				&& codecs[i].getSupportedOutputFormat().matches(destination)
			) {
				codec = codecs[i];
				destinationFormat = destination;
				sourceFormat = source;
				return processFrame(frame, codec);
			}
		}

		//return frame without changes
		return frame;
    }
}
