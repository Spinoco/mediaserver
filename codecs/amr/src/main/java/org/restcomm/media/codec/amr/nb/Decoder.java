package org.restcomm.media.codec.amr.nb;

import org.mobicents.media.server.spi.format.Format;
import org.mobicents.media.server.spi.format.FormatFactory;
import org.restcomm.media.codec.amr.AMRDecoder;

public class Decoder extends AMRDecoder {
    public final static Format amr = FormatFactory.createAudioFormat("amr", 8000, 8, 1);
    private final static String AMR_CODEC_NAME = "libopencore_amrnb";
    private final static int AMR_SAMPLE_RATE= 8000;
    public final static int[] payloadSizes = { 12, 13, 15, 17, 19, 20, 26, 31, 5, 0, 0, 0, 0, 0, 0, 0 };

    public Decoder() {
        super(AMR_CODEC_NAME, AMR_SAMPLE_RATE, payloadSizes);
    }

    @Override
    public Format getSupportedInputFormat() {
        return amr;
    }

}
