package org.restcomm.media.codec.amr.nb;

import org.mobicents.media.server.spi.format.Format;
import org.mobicents.media.server.spi.format.FormatFactory;
import org.restcomm.media.codec.amr.AMRDecoder;

public class Decoder extends AMRDecoder {

    private final static Format amr = FormatFactory.createAudioFormat("amr", 8000, 8, 1);

    private final static int AMR_CODEC_ID = 73728;
    private final static int AMR_SAMPLE_RATE= 8000;

    public Decoder() {
        super(AMR_CODEC_ID, AMR_SAMPLE_RATE);
    }

    @Override
    public Format getSupportedInputFormat() {
        return amr;
    }

}
