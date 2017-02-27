package org.mobicents.media.server.impl.dsp.audio.opus;

import com.sun.jna.ptr.PointerByReference;
import org.mobicents.media.server.spi.dsp.Codec;
import org.mobicents.media.server.spi.format.Format;
import org.mobicents.media.server.spi.format.FormatFactory;
import org.mobicents.media.server.spi.memory.Frame;
import org.mobicents.media.server.spi.memory.Memory;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;

import static org.mobicents.media.server.impl.dsp.audio.opus.OpusLibrary.OPUS_APPLICATION_VOIP;

/**
 * Created by pach on 23/12/16.
 */
public class Encoder implements Codec {

    private final static Format opus = FormatFactory.createAudioFormat("opus", 48000, 16, 2);
    private final static Format linear = FormatFactory.createAudioFormat("linear", 8000, 16, 1);

    private IntBuffer error = IntBuffer.allocate(4);
    private PointerByReference encoder = OpusLibrary.INSTANCE.opus_encoder_create(8000, 1, OPUS_APPLICATION_VOIP, error);
    private ByteBuffer buff = ByteBuffer.allocateDirect(320);
    private ByteBuffer out = ByteBuffer.allocateDirect(4000);

    @Override
    public Format getSupportedInputFormat() {
        return linear;
    }

    @Override
    public Format getSupportedOutputFormat() {
        return opus;
    }

    @Override
    public Frame process(Frame frame) {
        System.out.println("XXXG OPUS ENC RECEIVED: "
                + "*" + error.get(0)
                + " L: " + frame.getLength()
                + " S: " + frame.getSequenceNumber()
                + " O: "  + frame.getOffset()
                + " D: " + frame.getDuration()
                + " T: " + frame.getTimestamp()
                + " F: " + frame.getFormat()
                + " H: " + frame.getHeader()
        );

        buff.clear();
        out.clear();
        buff.put(frame.getData(),frame.getOffset(),frame.getLength());
        buff.rewind();
        System.out.println("ABOUT TO ENCODE");
        int samples = OpusLibrary.INSTANCE.opus_encode(encoder,buff.asShortBuffer(),160,out,4000);
        System.out.println("ENCODED" + samples);

        Frame res = Memory.allocate(samples);
        out.get(res.getData());


        res.setOffset(0);
        res.setLength(samples);
        res.setTimestamp(frame.getTimestamp());
        res.setDuration(frame.getDuration());
        res.setSequenceNumber(frame.getSequenceNumber());
        res.setEOM(frame.isEOM());
        res.setFormat(opus);
        res.setHeader(frame.getHeader());
        System.out.println("XXXG OPUS ENCODED: "
                + "*" + error.get(0)
                + " L: " + res.getLength()
                + " S: " + res.getSequenceNumber()
                + " O: "  + res.getOffset()
                + " D: " + res.getDuration()
                + " T: " + res.getTimestamp()
                + " F: " + res.getFormat()
                + " H: " + res.getHeader()
        );
        return res;
    }
}
