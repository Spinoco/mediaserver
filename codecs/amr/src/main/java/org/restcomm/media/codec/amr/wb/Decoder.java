package org.restcomm.media.codec.amr.wb;

import org.mobicents.media.server.spi.format.Format;
import org.mobicents.media.server.spi.format.FormatFactory;
import org.restcomm.media.codec.amr.AMRDecoder;


public class Decoder extends AMRDecoder {
    public final static Format amr_wb = FormatFactory.createAudioFormat("amr-wb", 16000, 8, 1);
    private final static String AMR_WB_CODEC_NAME = "libopencore_amrwb";
    private final static int AMR_WB_SAMPLE_RATE = 16000;
    public final static int[] payloadSizes = {18, 24, 33, 37, 41, 47, 51, 59, 61, 6, 6, 0, 0, 0, 1, 1};

    public Decoder() {
        super(AMR_WB_CODEC_NAME, AMR_WB_SAMPLE_RATE, payloadSizes);
    }

    @Override
    public Format getSupportedInputFormat() {
        return amr_wb;
    }
}
