import org.mobicents.media.server.impl.rtp.RtpPacket;

import java.nio.ByteBuffer;

/**
 * Created by pach on 21/09/16.
 */
public  class RTPPacketDecodeApp {



    public static void main(String[] args) {

        String data = "80082CF100210CA0365BE79C196B273BEE9C6D87B3B49FEF9DF1070716F0B48ADD8BBD0E3A1C0227368697D8B5B28DE1EAEF1D0304638EB1FE94B25A25371D3D3CF883E884B6B59344F74B060D1EE9B6B56383B20B2707183B358C83F98CB08CE65D4117030E118EB286E4B7833F2506023E01B4B49D8FB3836B146D070E0F40B3BDE79FBD60263E07373D64B0B18188B19D041E1C0C0F0493BEB05D81B00F21300631359FB3B68D8B8BD501";
        byte[] bytes = hexStringToByteArray(data);

        RtpPacket rtpPacket = null; //new RtpPacket(bytes.length, true);
//
//        ByteBuffer buffer = rtpPacket.getBuffer();
//        buffer.clear();
//        buffer.put(bytes, 0, bytes.length);
//        buffer.flip();

        System.out.println("Packet Is: " + rtpPacket.toString());
    }



    public static byte[] hexStringToByteArray(String s) {
        int len = s.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(s.charAt(i), 16) << 4)
                    + Character.digit(s.charAt(i+1), 16));
        }
        return data;
    }
}
