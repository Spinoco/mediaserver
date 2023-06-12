package org.restcomm.media.codec.amr.wb;

import org.mobicents.media.server.spi.dsp.Codec;
import org.mobicents.media.server.spi.format.Format;
import org.mobicents.media.server.spi.format.FormatFactory;
import org.mobicents.media.server.spi.memory.Frame;
import org.mobicents.media.server.spi.memory.Memory;

public class Encoder implements Codec {

    private final static Format amr = FormatFactory.createAudioFormat("amr_wb", 16000, 8, 1);
    private final static Format linear = FormatFactory.createAudioFormat("linear", 8000, 16, 1);

    public Encoder() {
    }

    @Override
    public Format getSupportedInputFormat() {
        return linear;
    }

    @Override
    public Format getSupportedOutputFormat() {
        return amr;
    }

    @Override
    public Frame process(Frame frame) {
        Frame res = Memory.allocate(0);
        res.setOffset(0);
        res.setLength(0);
        res.setFormat(amr);
        res.setTimestamp(frame.getTimestamp());
        res.setDuration(frame.getDuration());
        res.setEOM(frame.isEOM());
        res.setSequenceNumber(frame.getSequenceNumber());

        return res;
    }
}
