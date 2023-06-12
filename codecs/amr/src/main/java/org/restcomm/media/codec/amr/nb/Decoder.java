package org.restcomm.media.codec.amr.nb;

import org.mobicents.media.server.spi.format.Format;
import org.mobicents.media.server.spi.format.FormatFactory;
import org.restcomm.media.codec.amr.AMRDecoder;

public class Decoder extends AMRDecoder {
    public final static Format amr = FormatFactory.createAudioFormat("amr", 8000, 8, 1);
    private final static String AMR_CODEC_NAME = "libopencore_amrnb";
    private final static int AMR_SAMPLE_RATE= 8000;

    // Real payload sizes are as bellow, we are compensating here for toc.
    // This compensation happens only in NB AMR.
    // { 12, 13, 15, 17, 19, 20, 26, 31, 5, 0, 0, 0, 0, 0, 0, 0 }
    public final static int[] payloadSizes = { 13, 14, 16, 18, 20, 21, 27, 32, 6, 0, 0, 0, 0, 0, 0, 0 };

    public Decoder() {
        super(AMR_CODEC_NAME, AMR_SAMPLE_RATE, payloadSizes);
    }

    @Override
    public Format getSupportedInputFormat() {
        return amr;
    }

}
