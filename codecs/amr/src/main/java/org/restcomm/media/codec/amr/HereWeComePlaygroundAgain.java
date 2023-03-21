package org.restcomm.media.codec.amr;

import org.mobicents.media.server.spi.memory.Frame;
import org.mobicents.media.server.spi.memory.Memory;
import org.restcomm.media.codec.amr.wb.Decoder;

public class HereWeComePlaygroundAgain {


    public static byte[] hexStringToByteArray(String s) {
        int len = s.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(s.charAt(i), 16) << 4)
                    + Character.digit(s.charAt(i+1), 16));
        }
        return data;
    }

    private static final char[] HEX_ARRAY = "0123456789ABCDEF".toCharArray();
    public static String bytesToHex(byte[] bytes) {
        char[] hexChars = new char[bytes.length * 2];
        for (int j = 0; j < bytes.length; j++) {
            int v = bytes[j] & 0xFF;
            hexChars[j * 2] = HEX_ARRAY[v >>> 4];
            hexChars[j * 2 + 1] = HEX_ARRAY[v & 0x0F];
        }
        return new String(hexChars);
    }

    public static void main(String[] args) {
        // 1111 0100 0111 0100
        // 1111 0001 0111 0000
        // 1000 = 8
        // 0010 = 2
        // 0100 0100


        String data = "f474802e98751a2cff802c6443a19ae3dd77fa15da6c845e292263fbc1a28d4c12f66a835380080cb10fb3d67cda5bf6959b76b5e94571eec65a24f07e";
        String data2 = "f170206d08d9b946589f9e9a3574b7f35321a3e33043c038575ab73b5522c9fc4a";
        String data3 = "f4c00000000200";

        byte[] dataB = hexStringToByteArray(data2);


        byte[] aligned = AMRDecoder.octetAlign(dataB, Decoder.payloadSizes);
        byte[] alignedWithHeader = new byte[aligned.length + 1];

        System.arraycopy(aligned, 0, alignedWithHeader, 1, aligned.length);

        byte[] alignedStripped = AMRDecoder.stripCMR(alignedWithHeader, Decoder.payloadSizes);

        System.out.println(Integer.toHexString((aligned[0]& 0x78) >> 3));
        System.out.println(Integer.toHexString((alignedStripped[0]& 0x78) >> 3));




//        Decoder dec = new Decoder();

//        Frame frame = Memory.allocate(0);
//
//        frame.setData(dataB);
//        frame.setTimestamp(0);
//        frame.setSequenceNumber(0);
//        frame.setEOM(false);
//        frame.setHeader("");
//        frame.setFormat(Decoder.amr_wb);
//
//        Frame frame2 = dec.process(frame);
//
//        System.out.println("XXXX DATA OUT SIZE? " + bytesToHex(frame2.getData()));



    }


}
