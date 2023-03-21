package org.restcomm.media.codec.g722;

import org.mobicents.media.server.spi.format.Format;
import org.mobicents.media.server.spi.format.FormatFactory;
import org.restcomm.media.codec.ffmpeg.FFMPEGDecoder;

public class Decoder extends FFMPEGDecoder {

    private final static Format g722 = FormatFactory.createAudioFormat("g722", 16000, 8, 1);

    private final static String G722_CODEC_NAME = "g722";
    private final static int G722_SAMPLE_RATE = 16000;

    public Decoder() {
        super(G722_CODEC_NAME, G722_SAMPLE_RATE);
    }

    @Override
    public Format getSupportedInputFormat() {
        return g722;
    }
}
