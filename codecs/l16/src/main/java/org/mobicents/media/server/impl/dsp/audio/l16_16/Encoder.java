package org.mobicents.media.server.impl.dsp.audio.l16_16;

import org.mobicents.media.server.spi.dsp.Codec;
import org.mobicents.media.server.spi.format.Format;
import org.mobicents.media.server.spi.format.FormatFactory;
import org.mobicents.media.server.spi.memory.Frame;
import org.mobicents.media.server.spi.memory.Memory;

public class Encoder implements Codec {

    private final static Format l16_16 = FormatFactory.createAudioFormat("linear", 16000, 16, 1);
    private final static Format linear = FormatFactory.createAudioFormat("linear", 8000, 16, 1);

    public Frame process(Frame frame) {
        Frame res = Memory.allocate(640);
        byte[] src = frame.getData();
        byte[] dest = res.getData();
        for ( int i = 0,  j=0; i < src.length; i++, j+=2) {
            dest[j] = src[i];
            dest[j+1] = src[i];
        }

        res.setOffset(0);
        res.setLength(320);
        res.setTimestamp(frame.getTimestamp());
        res.setDuration(frame.getDuration());
        res.setSequenceNumber(frame.getSequenceNumber());
        res.setEOM(frame.isEOM());
        res.setFormat(l16_16);
        return res;
    }

    public Format getSupportedInputFormat() {
        return linear;
    }

    public Format getSupportedOutputFormat() {
        return l16_16;
    }
}
