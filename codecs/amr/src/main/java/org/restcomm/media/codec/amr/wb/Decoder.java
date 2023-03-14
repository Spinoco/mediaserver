package org.restcomm.media.codec.amr.wb;

import org.mobicents.media.server.spi.format.Format;
import org.mobicents.media.server.spi.format.FormatFactory;
import org.restcomm.media.codec.amr.AMRDecoder;


public class Decoder extends AMRDecoder {
    private final static Format amr_wb = FormatFactory.createAudioFormat("amr-wb", 16000, 8, 1);
    private final static int AMR_WB_CODEC_ID = 73729;

    public Decoder() {
        super(AMR_WB_CODEC_ID);
    }

    @Override
    public Format getSupportedInputFormat() {
        return amr_wb;
    }
}
