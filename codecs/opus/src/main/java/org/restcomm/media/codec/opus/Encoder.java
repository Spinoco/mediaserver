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
import java.nio.ShortBuffer;

import org.apache.commons.lang3.RandomStringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.mobicents.media.server.spi.dsp.Codec;
import org.mobicents.media.server.spi.format.Format;
import org.mobicents.media.server.spi.format.FormatFactory;
import org.mobicents.media.server.spi.memory.Frame;
import org.mobicents.media.server.spi.memory.Memory;

/**
 * Implements Opus encoder.
 *
 * @author Vladimir Morosev (vladimir.morosev@telestax.com)
 *
 */
public class Encoder implements Codec {

    private final static Logger log = LogManager.getLogger(Encoder.class);

    private final static Format opus = FormatFactory.createAudioFormat("opus", 48000, 8, 2);
    private final static Format linear = FormatFactory.createAudioFormat("linear", 8000, 16, 1);


    private volatile long encoderId = 0;

    private final int OPUS_SAMPLE_RATE = 8000;
    private final int BITRATE = 32000;
    private final int MAX_PACKET_SIZE = 320;

    private byte[] encodedBuff = new byte[MAX_PACKET_SIZE];

    public Encoder() {
    }

    @Override
    protected void finalize() throws Throwable {
        if (encoderId != 0) OpusNative.destroyEncoder(encoderId);
        super.finalize();
    }

    @Override
    public Format getSupportedInputFormat() {
        return linear;
    }

    @Override
    public Format getSupportedOutputFormat() {
        return opus;
    }

    @Override
    public Frame process(Frame frame) {

        // lazily init encoder, as we do not want to always spawn
        // when java abject is initiated. It seems to be initiated pretty often at different places
        // and then recycled without doing any work at all.
        if (this.encoderId == 0) {
            try {
                this.encoderId = OpusNative.createEncoder(OPUS_SAMPLE_RATE, 1, OpusNative.OPUS_APPLICATION_VOIP, BITRATE);
            } catch (Throwable t) {
                log.error("Failed to instantiate opus encoder", t);
            }
        }

        int frameLength = frame.getData().length;
        short[] pcm = new short[frameLength / 2];

        ByteBuffer.wrap(frame.getData()).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(pcm);
        int encoded = OpusNative.encode(encoderId, pcm, encodedBuff);

        if (encoded > 0) {
            Frame res = Memory.allocate(encoded);
            System.arraycopy(encodedBuff, 0, res.getData(), 0, encoded);

            res.setOffset(0);
            res.setLength(encoded);
            res.setFormat(opus);
            res.setTimestamp(frame.getTimestamp());
            res.setDuration(frame.getDuration());
            res.setEOM(frame.isEOM());
            res.setSequenceNumber(frame.getSequenceNumber());

            return res;

        } else {
            log.error("Failed to encode opus packet: " + encoded + " frame: " + frame);
            return null;
        }
    }
}
