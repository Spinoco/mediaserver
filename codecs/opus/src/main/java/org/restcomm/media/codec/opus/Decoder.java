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

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

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

    private final static Logger log = LogManager.getLogger(Decoder.class);

    private final static Format opus = FormatFactory.createAudioFormat("opus", 48000, 8, 2);
    private final static Format linear = FormatFactory.createAudioFormat("linear", 8000, 16, 1);

    private volatile long decoderId = 0;

    private final int OPUS_SAMPLE_RATE = 8000;
    private final int MAX_FRAME_SIZE = 160;
    private final int SAMPLE_LENGTH = 1000000 / OPUS_SAMPLE_RATE; // 1s / 8Khz ~ 125000ns / sample


    private short[] decodedBuff = new short[MAX_FRAME_SIZE];

    public Decoder() {
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

        // lazily init decoder, as we do not want to always spawn
        // when java abject is initiated. It seems to be initiated pretty often at different places
        // and then recycled without doing any work at all.
        if (this.decoderId == 0) {
            try {
                this.decoderId = OpusNative.createDecoder(OPUS_SAMPLE_RATE, 1);
            } catch (Throwable t) {
                log.error("Failed to instantiate opus decoder", t);
            }
        }

        int frameSize = OpusNative.decode(decoderId, frame.getData(), decodedBuff, 0);

        if (frameSize > 0) {

            Frame res = Memory.allocate(frameSize * 2);

            ByteBuffer.wrap(res.getData()).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(decodedBuff, 0, frameSize);

            res.setOffset(0);
            res.setLength(frameSize * 2);
            res.setTimestamp(frame.getTimestamp());
            res.setDuration(frameSize * SAMPLE_LENGTH);
            res.setSequenceNumber(frame.getSequenceNumber());
            res.setEOM(frame.isEOM());
            res.setFormat(linear);
            res.setHeader(frame.getHeader());

            return res;
        } else {
            log.error(
                "Failed to decode Opus packet."
                   + "err="  + frameSize
                   + ", source=" + frame
                   + ", content=" + (frame.getData() == null ? "NULL" : new BigInteger(frame.getData()).toString(16))
            );

            // to make sure the call still takes next samples lets make this sample a silence
            // and continue. This may improve compatibility, i.e. for unsupported packets
            // assume 160 samples in silent frame
            Frame res = Memory.allocate(MAX_FRAME_SIZE * 2);
            Arrays.fill(res.getData(), (byte)0xFF);
            res.setOffset(0);
            res.setLength(MAX_FRAME_SIZE * 2);
            res.setTimestamp(frame.getTimestamp());
            res.setDuration(MAX_FRAME_SIZE * SAMPLE_LENGTH);
            res.setSequenceNumber(frame.getSequenceNumber());
            res.setEOM(frame.isEOM());
            res.setFormat(linear);
            res.setHeader(frame.getHeader());

            return res;
        }
    }
}