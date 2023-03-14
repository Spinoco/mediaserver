package org.restcomm.media.codec.amr;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.mobicents.media.server.spi.dsp.Codec;
import org.mobicents.media.server.spi.format.Format;
import org.mobicents.media.server.spi.format.FormatFactory;
import org.mobicents.media.server.spi.memory.Frame;
import org.mobicents.media.server.spi.memory.Memory;
import org.restcomm.media.codec.amr.nb.Decoder;

import java.math.BigInteger;
import java.util.Arrays;

/**
 * Support for codec decoder using FFMEPG.
 * This will always return our internal linear data format.
 */
abstract public class FFMPEGDecoder implements Codec {

    private final static Logger log = LogManager.getLogger(Decoder.class);

    private final static Format linear = FormatFactory.createAudioFormat("linear", 8000, 16, 1);

    private volatile long decoderId = 0;
    private final int ffmpeg_codec_id;

    // 320 bytes required to contain single linear codec.
    private final byte[] decodedBuff = new byte[320];

    public FFMPEGDecoder(int codecId) {
        this.ffmpeg_codec_id = codecId;
    }

    @Override
    protected void finalize() throws Throwable {
        if (this.decoderId != 0) FFMPEGNative.destroyDecoder(this.decoderId);
        super.finalize();
    }

    @Override
    public Format getSupportedOutputFormat() {
        return linear;
    }

    @Override
    public Frame process(Frame frame) {

        // lazily init decoder, as we do not want to always spawn
        // when java abject is initiated. It seems to be initiated pretty often at different places
        // and then recycled without doing any work at all.
        if (this.decoderId == 0) {
            try {
                this.decoderId = FFMPEGNative.createDecoder(ffmpeg_codec_id);
            } catch (Throwable t) {
                log.error("Failed to instantiate amr decoder", t);
            }
        }

        int frameSize = FFMPEGNative.decode(decoderId, decodedBuff, frame.getData());

        if (frameSize > 0) {

            Frame res = Memory.allocate(320);
            System.arraycopy(decodedBuff, 0, res.getData(), 0, frameSize);

            res.setOffset(0);
            res.setLength(frameSize);
            res.setTimestamp(frame.getTimestamp());
            res.setDuration(160);
            res.setSequenceNumber(frame.getSequenceNumber());
            res.setEOM(frame.isEOM());
            res.setFormat(linear);
            res.setHeader(frame.getHeader());

            return res;
        } else {
            log.error(
                    "Failed to decode AMR packet."
                            + "err="  + frameSize
                            + ", source=" + frame
                            + ", content=" + (frame.getData() == null ? "NULL" : new BigInteger(frame.getData()).toString(16))
            );

            // to make sure the call still takes next samples lets make this sample a silence
            // and continue. This may improve compatibility, i.e. for unsupported packets
            // assume 160 samples in silent frame
            Frame res = Memory.allocate(360);
            Arrays.fill(res.getData(), (byte)0xFF);
            res.setOffset(0);
            res.setLength(360);
            res.setTimestamp(frame.getTimestamp());
            res.setDuration(160);
            res.setSequenceNumber(frame.getSequenceNumber());
            res.setEOM(frame.isEOM());
            res.setFormat(linear);
            res.setHeader(frame.getHeader());

            return res;
        }
    }
}
