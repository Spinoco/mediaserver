/*
 * TeleStax, Open Source Cloud Communications
 * Copyright 2011-2017, Telestax Inc and individual contributors
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

package org.restcomm.media.codec.opus;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import org.apache.commons.lang3.RandomStringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.mobicents.media.server.spi.dsp.Codec;
import org.mobicents.media.server.spi.format.Format;
import org.mobicents.media.server.spi.format.FormatFactory;
import org.mobicents.media.server.spi.memory.Frame;
import org.mobicents.media.server.spi.memory.Memory;

/**
 * Implements Opus decoder.
 *
 * @author Vladimir Morosev (vladimir.morosev@telestax.com)
 *
 */
public class Decoder implements Codec {

    private final static Logger log = LogManager.getLogger(Encoder.class);

    private final static Format opus = FormatFactory.createAudioFormat("opus", 48000, 8, 2);
    private final static Format linear = FormatFactory.createAudioFormat("linear", 8000, 16, 1);

    private long decoderId = 0;

    private final int OPUS_SAMPLE_RATE = 48000;
    private final int MAX_FRAME_SIZE = 6*480;

    private short[] decodedBuff = new short[MAX_FRAME_SIZE];

    public Decoder() {
        this.decoderId = OpusNative.createDecoder(OPUS_SAMPLE_RATE, 1);
    }

    @Override
    protected void finalize() throws Throwable {
        if (this.decoderId != 0) OpusNative.destroyDecoder(this.decoderId);
        super.finalize();
    }

    @Override
    public Format getSupportedInputFormat() {
        return opus;
    }

    @Override
    public Format getSupportedOutputFormat() {
        return linear;
    }

    @Override
    public Frame process(Frame frame) {
        System.out.println("ABOUT TO DECODE " + frame.toString());

        int frameSize = OpusNative.decode(decoderId, frame.getData(), decodedBuff);

        if (frameSize > 0) {

            Frame res = Memory.allocate(frameSize * 2);
            System.arraycopy(decodedBuff, 0, res.getData(), 0, frameSize * 2);

            res.setOffset(0);
            res.setLength(frameSize*2);
            res.setTimestamp(frame.getTimestamp());
            res.setDuration(frame.getDuration());
            res.setSequenceNumber(frame.getSequenceNumber());
            res.setEOM(frame.isEOM());
            res.setFormat(linear);
            res.setHeader(frame.getHeader());

            return res;
        } else {
            log.error("Failed to decode Opus packet: " + frameSize + " source: " + frame);
            return null;
        }
    }
}