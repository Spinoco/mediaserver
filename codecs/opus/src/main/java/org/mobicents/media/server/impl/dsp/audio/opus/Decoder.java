package org.mobicents.media.server.impl.dsp.audio.opus;

import com.sun.jna.ptr.PointerByReference;
import org.mobicents.media.server.spi.dsp.Codec;
import org.mobicents.media.server.spi.format.Format;
import org.mobicents.media.server.spi.format.FormatFactory;
import org.mobicents.media.server.spi.memory.Frame;
import org.mobicents.media.server.spi.memory.Memory;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;

/**
 * Created by pach on 23/12/16.
 */
public class Decoder implements Codec {

    private final static Format opus = FormatFactory.createAudioFormat("opus", 48000, 16, 2);
    private final static Format linear = FormatFactory.createAudioFormat("linear", 8000, 16, 1);

    private IntBuffer error = IntBuffer.wrap(new int[] {OpusLibrary.OPUS_OK});
    private PointerByReference decoder = OpusLibrary.INSTANCE.opus_decoder_create(8000, 2, error);
    private ByteBuffer buff = ByteBuffer.allocateDirect(320);
    @Override
    public Format getSupportedInputFormat() {
        return opus;
    }

    @Override
    public Format getSupportedOutputFormat() {
        return linear;
    }

    @Override
    public Frame process(Frame frame) {
        System.out.println("XXXG OPUS RECEIVED: "
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
        int samples = OpusLibrary.INSTANCE.opus_decode(decoder,frame.getData(),frame.getLength(),buff.asShortBuffer(),160,0);
      //  System.out.println("DECODED OPUS: " + samples + " L: " +buff.limit() + " C: "+ buff.capacity() +" P: "+ buff.position());

        Frame res = Memory.allocate(320);
        buff.get(res.getData());


        res.setOffset(0);
        res.setLength(320);
        res.setTimestamp(frame.getTimestamp());
        res.setDuration(frame.getDuration());
        res.setSequenceNumber(frame.getSequenceNumber());
        res.setEOM(frame.isEOM());
        res.setFormat(linear);
        res.setHeader(frame.getHeader());
        System.out.println("XXXG OPUS DECODED: "
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
