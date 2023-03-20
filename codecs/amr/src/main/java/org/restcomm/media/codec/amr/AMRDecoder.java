package org.restcomm.media.codec.amr;

import org.mobicents.media.server.spi.memory.Frame;
import org.restcomm.media.codec.ffmpeg.FFMPEGDecoder;

/**
 * AMR decoder for RTP payload specified in
 * <a href="https://www.rfc-editor.org/rfc/rfc4867.htm">RFC4867</a>
 */
abstract public class AMRDecoder extends FFMPEGDecoder {

    /**
     * If payload is in bandwidth efficient mode, this will align it to octet aligned mode.
     * At the same time we strip the first byte which contains CMR which is not needed for processing.
     *
     * NOTE: This only supports single frame in one RTP payload.
     *
     * @param amrData  Data in the RTP payload.
     */
    static public byte[] octetAlign(byte[] amrData) {

        byte firstByte = amrData[0];
        byte secondByte = amrData[1];

        int f = firstByte & 0x08;
        int ftp1 = (firstByte & 0x07) << 1; // First part of FT
        int ftp2 = (secondByte & 0x80) >> 7; // Second part of FT
        int q = (secondByte & 0x40) >> 6;
        int ft = ftp1 | ftp2;

        int offsetBy = 2;

        byte[] retData = new byte[amrData.length + 1];


        retData[0] = (byte)( //TOC byte
                ((f << 7) & 0x80) |
                ((ft << 3) & 0x78) |
                ((q << 2) & 0x04)
        );


        int retDataIx = 1;

        for (int i = 1; i < amrData.length; i++) {
            byte nextByte = (i + 1) >= amrData.length ? (byte) 0xFF : amrData[i + 1];
            byte current = amrData[i];

            int data = (current << offsetBy) | ((nextByte >> (8 - offsetBy)) & 0x03) ;

            retData[retDataIx] = (byte) data;
            retDataIx++;
        }


        return retData;
    }

    /**
     * In octet aligned mode we only want to strip away the first byte which contains CRM.
     * Rest of the payload is correctly aligned for processing via FFMPEG.
     *
     * @param amrData  Data in the RTP payload.
     */
    static public byte[] stripCMR(byte[] amrData) {
        byte[] retData = new byte[amrData.length -1];

        System.arraycopy(amrData, 1, retData, 0, retData.length);

        return retData;
    }

    public AMRDecoder(int codecId, int sampleRate) {
        super(codecId, sampleRate);
    }

    @Override
    public Frame process(Frame frame) {

        byte[] aligned;

        // Check if the format of the data has assigned format options.
        if (frame.getFormat().getOptions() != null) {
            String options = frame.getFormat().getOptions().toString();

            // if the format is marked to be octet aligned, only strip first byte for processing.
            // other cases mean we are in bandwidth efficient mode, and we need to octet align the data.
            if (options.contains("octet-align=1")) aligned = stripCMR(frame.getData());
            else aligned = octetAlign(frame.getData());

        } else {
            // Default behaviour is octec align data.
            aligned = octetAlign(frame.getData());
        }

        // Update data with the newly aligned / stripped data;
        frame.setData(aligned);

        return super.process(frame);
    }
}
