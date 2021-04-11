package org.mobicents.media.server.impl.dsp.audio.l16_24;

import org.mobicents.media.server.spi.dsp.Codec;
import org.mobicents.media.server.spi.format.Format;
import org.mobicents.media.server.spi.format.FormatFactory;
import org.mobicents.media.server.spi.memory.Frame;
import org.mobicents.media.server.spi.memory.Memory;


public class Decoder implements Codec {

    private final static Format l16_24 = FormatFactory.createAudioFormat("linear", 24000, 16, 1);

    private final static Format linear = FormatFactory.createAudioFormat("linear", 8000, 16, 1);

    public Frame process(Frame frame) {
        Frame res = Memory.allocate(320);
        byte[] dest = res.getData();
        byte[] src = frame.getData();
        for (int i=0, j=0; i < src.length; i+=3, j++) {
            dest[j] = src[i];
        }
        res.setOffset(0);
        res.setLength(320);
        res.setTimestamp(frame.getTimestamp());
        res.setDuration(frame.getDuration());
        res.setSequenceNumber(frame.getSequenceNumber());
        res.setEOM(frame.isEOM());
        res.setFormat(linear);
        return res;
    }

    public Format getSupportedInputFormat() {
        return l16_24;
    }

    public Format getSupportedOutputFormat() {
        return linear;
    }
}
