package org.restcomm.media.codec.amr;

import org.mobicents.media.server.spi.memory.Frame;
import org.mobicents.media.server.spi.memory.Memory;
import org.restcomm.media.codec.ffmpeg.FFMPEGDecoder;

import java.util.Arrays;

/**
 * AMR decoder for RTP payload specified in
 * <a href="https://www.rfc-editor.org/rfc/rfc4867.htm">RFC4867</a>
 */
abstract public class AMRDecoder extends FFMPEGDecoder {

    // Supported sizes of payload of a frame that should be decoded.
    // Input data will be striped to the sizes according to their "FT" in TOC.
    private final int[] payloadSize;

    /**
     * If payload is in bandwidth efficient mode, this will align it to octet aligned mode.
     * At the same time we strip the first byte which contains CMR which is not needed for processing.
     * At the same time we will ensure that the payload is of correct sizing for a single frame of AMR data.
     *
     * NOTE: This only supports single frame in one RTP payload.
     *
     * @param amrData       Data in the RTP payload.
     * @param payloadSizes  The supported payload sizing of the AMR frame.
     */
    static public byte[] octetAlign(byte[] amrData, int[] payloadSizes) {

        byte firstByte = amrData[0];
        byte secondByte = amrData[1];

        int f = firstByte & 0x08;
        int ftp1 = (firstByte & 0x07) << 1; // First part of FT
        int ftp2 = (secondByte & 0x80) >> 7; // Second part of FT
        int q = (secondByte & 0x40) >> 6;
        int ft = ftp1 | ftp2;

        if (ft < payloadSizes.length) {
            int payloadSize = payloadSizes[ft];
            byte[] retData = new byte[payloadSize];

            // Octet aligned TOC.
            int toc = ((f << 7) & 0x80) |
                    ((ft << 3) & 0x78) |
                    ((q << 2) & 0x04);

            retData[0] = (byte) toc;

            // The amount by which to offset data in payload
            // This is needed since the header of the payload takes 1 byte and 2 bits from second byte.
            // As such when we are transcoding to octet aligned mode we need to shift all data
            // in payload by 2 bits.
            int offsetBy = 2;
            for (int i = 1; i < retData.length; i++) {
                byte nextByte = (i + 1) >= amrData.length ? (byte) 0xFF : amrData[i + 1];
                byte current = amrData[i];

                int data = (current << offsetBy) | ((nextByte >> (8 - offsetBy)) & 0x03) ;

                retData[i] = (byte) data;
            }

            return retData;


        } else {
            return new byte[0];
        }
    }

    /**
     * In octet aligned mode we only want to strip away the first byte which contains CRM.
     * Rest of the payload is correctly aligned for processing via FFMPEG.
     * At the same time we will ensure that the payload is of correct sizing for a single frame of AMR data.
     *
     * @param amrData       Data in the RTP payload.
     * @param payloadSizes  The supported payload sizing of the AMR frame.
     */
    static public byte[] stripCMR(byte[] amrData, int[] payloadSizes) {
        // Ensure we have TOC.
        if (amrData.length >= 2) {
            int ft = (amrData[1] & 0x78) >> 3;
            if (ft < payloadSizes.length) {
                int payloadSize = payloadSizes[ft];
                byte[] retData = new byte[payloadSize];
                System.arraycopy(amrData, 1, retData, 0, payloadSize - 1);

                return retData;
            } else {
                return new byte[0];
            }
        } else {
            return new byte[0];
        }
    }

    public AMRDecoder(String codecName, int sampleRate, int[] payloadSize) {
        super(codecName, sampleRate);
        this.payloadSize = payloadSize;
    }

    @Override
    public Frame process(Frame frame) {

        byte[] aligned;

        // Check if the format of the data has assigned format options.
        if (frame.getFormat().getOptions() != null) {
            String options = frame.getFormat().getOptions().toString();

            // if the format is marked to be octet aligned, only strip first byte for processing.
            // other cases mean we are in bandwidth efficient mode, and we need to octet align the data.
            if (options.contains("octet-align=1")) aligned = stripCMR(frame.getData(), this.payloadSize);
            else aligned = octetAlign(frame.getData(), this.payloadSize);

        } else {
            // Default behaviour is octet align data.
            aligned = octetAlign(frame.getData(), this.payloadSize);
        }

        if (aligned.length == 0) {
            // In case the aligned data are invalid, emit empty frame.
            Frame res = Memory.allocate(320);
            Arrays.fill(res.getData(), (byte)0);
            res.setOffset(0);
            res.setLength(320);
            res.setTimestamp(frame.getTimestamp());
            res.setDuration(160);
            res.setSequenceNumber(frame.getSequenceNumber());
            res.setEOM(frame.isEOM());
            res.setFormat(getSupportedOutputFormat());
            res.setHeader(frame.getHeader());

            return res;
        } else {
            // Update data with the newly aligned / stripped data;
            frame.setData(aligned);

            return super.process(frame);
        }
    }
}
