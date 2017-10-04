package org.mobicents.media.server.impl.rtp;

import com.google.common.collect.Lists;
import com.google.common.io.BaseEncoding;
import org.apache.log4j.Logger;
import org.bouncycastle.util.encoders.BufferedEncoder;
import org.mobicents.media.server.scheduler.EventQueueType;
import org.mobicents.media.server.scheduler.PriorityQueueScheduler;
import org.mobicents.media.server.scheduler.RealTimeScheduler;
import org.mobicents.media.server.scheduler.Task;
import org.mobicents.media.server.spi.memory.Frame;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A dumper of RTP Packets as they have been received and send from the jitter buffer
 * This creates  2 files, that can be later used to replay whatever data this was received
 * and offered by jitter buffer.
 *
 * File is named based on the first packet (frame received) and on the index
 * that is received from the JitterBuffer.
 *
 * Note that for this to work a system property JITTER_BUFFER_DUMP_DIR must be set to existing directory
 * that contains file dump.cfg with addresses to be captures in following format:
 *
 * i.e.
 *
 * 193.138.10.1
 * 10
 * 193.127
 *
 * If the file is empty, all addresses are captured.
 *
 * The format of the .jbr (received) file is :
 *
 * timestamp (nano of write to JB) ; sequence ; timestamp_rtp ; format ; samples (hex)
 *
 * Format of the .jbs (supplied) file is
 *
 * timestamp (nano of read to JB) ; sequence of supplied packet (-1 if the queue was empty, and thus null was supplied) ; size_of_buffer
 *
 */
public class JitterBufferRTPDump {

    private static Logger logger =  Logger.getLogger(JitterBufferRTPDump.class) ;

    private PriorityQueueScheduler scheduler;
    private long index;


    private Path outputDir;
    private List<String> captureFilter;
    private BufferedWriter received;
    private BufferedWriter supplied;

    private long startTime;
    private long startTimeNano;

    private ConcurrentLinkedQueue<DumpFrame> queue = new ConcurrentLinkedQueue<>();
    private ConcurrentLinkedQueue<DumpSuppliedFrame> queueSupplied = new ConcurrentLinkedQueue<>();
    private AtomicInteger count = new AtomicInteger(0); // if this is set to -1 the recorder is closed for more updates

    private  BaseEncoding encoder = BaseEncoding.base16().lowerCase();

    /** gets dump directory from env if configured **/
    public static Path getDumpDir() {
        String cfg = System.getenv("JITTER_BUFFER_DUMP_DIR");
        if (cfg != null) try {
            return Paths.get(cfg);
        } catch (Throwable t) {
        }
        return null;
    }

    /** retunr null if dump is not configured or list of configured prefixes **/
    public static List<String> getDumpConfig(Path path) {
        if (path != null) {
            try {
                return Files.readAllLines(path.resolve("dump.cfg"));
            } catch (IOException e) {
                return null;
            }
        }
        return null;
    }


    public JitterBufferRTPDump(PriorityQueueScheduler scheduler, long index, Path outputDir, List<String> captureFilter) {
        this.scheduler = scheduler;
        this.index = index;
        this.outputDir = outputDir;
        this.startTime = System.currentTimeMillis();
        this.startTimeNano = System.nanoTime();
        this.captureFilter = captureFilter;
    }

    /** dumps incoming frame attaching the time of the dump **/
    public void dump(RtpPacket packet, int jbSize) {
        if (count.get() >= 0 && packet != null) {
            queue.offer(new DumpFrame(packet, System.nanoTime(), jbSize));
            if (count.incrementAndGet() > 100) {
                scheduleDump();
                count.set(0);
            }
        }
    }

    public void suppliedDump(long seq, int jbSize) {
        if (count.get() >= 0) {
           queueSupplied.offer(new DumpSuppliedFrame(seq, System.nanoTime(), jbSize));
        }
    }

    /** finalizes dump **/
    public void commit() {
        count.set(-1);
        scheduler.submit(new FinalizeDumpTask(), EventQueueType.RECORDING);
    }

    /** schedules dump of the packets, called once each 50 samples ~ each 2s **/
    private void scheduleDump() {
        scheduler.submit(new DumpTask(), EventQueueType.RECORDING);
    }

    /** return formatted delta of nanotime since start of the capture **/
    private String deltaNano(long nowNano) {
        long diff = nowNano - this.startTimeNano;
        return (diff/1000000L + "." + diff%1000000L);
    }

    private void writeOneReceivedSample(DumpFrame df, BufferedWriter w) throws IOException {
        if (w != null && df != null && df.packet != null) {
            byte[] dataRaw = new byte[df.packet.getPayloadLength()];
            df.packet.getPayload(dataRaw, 0);
 
            w.write(deltaNano(df.ts) + ";");
            w.write(df.packet.getSeqNumber() + ";");
            w.write(df.packet.getTimestamp()+ ";");
            w.write(df.packet.getPayloadType() + ";");
            w.write(df.jbSize+ ";");
            w.write(dataRaw.length+";");
            w.write(encoder.encode(dataRaw));
            w.newLine();

        }

    }

    private void writeOneSuppliedSample(DumpSuppliedFrame df, BufferedWriter w) throws IOException {
        if (w != null && df != null) {
            w.write(deltaNano(df.ts)+ ";");
            w.write(df.seq + ";");
            w.write( df.jbSize + "");
            w.newLine();
        }
    }


    private boolean shallCapture(String address) {
        for (String filter : this.captureFilter ) {
            if (address.startsWith(filter)) return true;
        }
        return (this.captureFilter.isEmpty());
    }


    /** write all samples in queue and recycle them **/
    private synchronized void writeSamples() {
        if (received == null) {
            DumpFrame f = queue.poll();
            if (f == null) return; // return if queue is empty, should not be ever the case
            try {
                SocketAddress remote = f.packet.getRemotePeer();

                if (! (remote instanceof InetSocketAddress)) {
                    count.set(-1);
                    return;
                } else {
                    String addressAsString = ((InetSocketAddress)remote).getAddress().getHostAddress();
                    if (!shallCapture(addressAsString)) {
                        count.set(-1);
                        return;
                    }

                    String addressAndPort = addressAsString + "_" + ((InetSocketAddress)remote).getPort();
                    String prefix = "" + this.startTime + "_" + addressAndPort;
                    received = Files.newBufferedWriter(this.outputDir.resolve(prefix+".jbr"));
                    supplied = Files.newBufferedWriter(this.outputDir.resolve(prefix+".jbs"));

                    received.write(
                            "RECEIVED RTP DUMP AT " + DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(this.startTime))
                            + " FROM " + f.packet.getRemotePeer()
                            + " TO: " + f.packet.getLocalPeer()
                            + " SSRC: " + Long.toHexString(f.packet.getSyncSource())
                    );
                    received.newLine();
                    received.write("timestamp ; sequence ; timestamp_rtp ; format ; jbrSize; sample length; sample hex");
                    received.newLine();

                    supplied.write(
                            "SUPPLIED RTP DUMP AT " + DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(this.startTime))
                            +" FROM " + f.packet.getRemotePeer()
                            + " TO: " + f.packet.getLocalPeer()
                    );
                    supplied.newLine();
                    supplied.write("timestamp ; sequence ; jbrSize ");
                    supplied.newLine();

                    writeOneReceivedSample(f, received);

                }
            } catch (IOException e) {
                logger.error("Failed to create outputStreams: " + this + "[" + f + "]", e);
            }

        }

        DumpFrame f = queue.poll();
        while(f != null) {
            try {
                writeOneReceivedSample(f, received);
            } catch (IOException e) {
                logger.error("Failed to write frame: " + this + "[" + f + "]", e);
            }
            f = queue.poll();
        }

        DumpSuppliedFrame fs = queueSupplied.poll();
        while(fs != null) {
            try {
                writeOneSuppliedSample(fs, supplied);
            } catch (IOException e) {
                logger.error("Failed to write frame supplied: " + this + "[" + f + "]", e);
            }
            fs = queueSupplied.poll();
        }
    }

    /** flush any samples in queue and recycle them, close files **/
    private synchronized void flush() {
        writeSamples();

        if (received != null) try {
            received.flush();
            received.close();
            received = null;
        } catch (IOException e) {
            logger.error("Failed to close (RECEIVED) outputStreams: " + this, e);
        }

        if (supplied != null) try {
            supplied.flush();
            supplied.close();
            supplied = null;
        } catch (IOException e) {
            logger.error("Failed to close (SUPPLIED) outputStreams: " + this, e);
        }

    }


    private class DumpFrame {
        private RtpPacket packet;
        private long ts ;
        private int jbSize;

        public DumpFrame(RtpPacket packet, long ts, int jbSize) {
            this.packet = packet;
            this.ts = ts;
            this.jbSize = jbSize;
        }

        @Override
        public String toString() {
            return "DumpFrame{" +
                    "packet=" + packet +
                    ", ts=" + ts +
                    ", jbSize=" + jbSize +
                    '}';
        }
    }

    private class DumpSuppliedFrame {
        private long seq;
        private long ts;
        private int jbSize;

        public DumpSuppliedFrame(long seq, long ts, int jbSize) {
            this.seq = seq;
            this.ts = ts;
            this.jbSize = jbSize;
        }

        @Override
        public String toString() {
            return "DumpSuppliedFrame{" +
                    "seq=" + seq +
                    ", ts=" + ts +
                    ", jbSize=" + jbSize +
                    '}';
        }
    }

    private class DumpTask extends Task {
        @Override
        public EventQueueType getQueueType() {
            return EventQueueType.RECORDING;
        }

        @Override
        public long perform() {
            writeSamples();

            return 0;
        }
    }

    private class FinalizeDumpTask extends Task {
        @Override
        public EventQueueType getQueueType() {
            return EventQueueType.RECORDING;
        }

        @Override
        public long perform() {
            flush();
            return 0;
        }
    }


}
