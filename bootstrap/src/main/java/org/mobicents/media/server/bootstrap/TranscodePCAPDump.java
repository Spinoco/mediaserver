package org.mobicents.media.server.bootstrap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.mobicents.media.server.impl.resource.audio.RecorderFileSink;
import org.mobicents.media.server.spi.memory.Frame;
import org.mobicents.media.server.spi.memory.Memory;
import org.restcomm.media.codec.amr.wb.Decoder;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Iterator;

public class TranscodePCAPDump {


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

    public static void main(String[] args) throws IOException {
        readDataAndTranscode();
    }


    public static Frame nodeToFrame(JsonNode node) {
        JsonNode rtp = node.get("_source").get("layers").get("rtp");

        String data = rtp.get("rtp.payload").asText().replace(":", "");

        byte[] dataB = hexStringToByteArray(data);

        Frame frame = Memory.allocate(dataB.length);
        frame.setData(dataB);
        frame.setTimestamp(0);
        frame.setSequenceNumber(0);
        frame.setEOM(false);
        frame.setHeader("");
        frame.setFormat(Decoder.amr_wb);

        return frame;
    }

    public static void readDataAndTranscode() throws IOException {
        Path filePath = Paths.get("/Users/adamchlupacek/Downloads/readMe2.json");
        Path filePathDest = Paths.get("/Users/adamchlupacek/Downloads/rec.wav");

        RecorderFileSink sink = new RecorderFileSink(filePathDest, false);

        String content = new String(Files.readAllBytes(filePath));

        ObjectMapper objectMapper = new ObjectMapper();

        ArrayNode array = (ArrayNode)objectMapper.readTree(content);

        Iterator<JsonNode> ele = array.elements();
        Frame[] out = new Frame[array.size()];

        int idx = 0;
        while (ele.hasNext()) {
            JsonNode node = ele.next();
            out[idx++] = nodeToFrame(node);
        }

        Decoder dec = new Decoder();

        for (Frame frame : out) {
            Frame frame2 = dec.process(frame);
            sink.write(ByteBuffer.wrap(frame2.getData()));
        }

        sink.commit();
    }

}
