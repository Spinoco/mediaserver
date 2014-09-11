package org.mobicents.media.server.impl.rtcp;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.SocketException;
import java.nio.channels.DatagramChannel;

import org.apache.log4j.Logger;
import org.mobicents.media.io.ice.IceAuthenticator;
import org.mobicents.media.server.impl.rtp.statistics.RtpStatistics;
import org.mobicents.media.server.impl.srtp.DtlsHandler;
import org.mobicents.media.server.impl.stun.StunHandler;
import org.mobicents.media.server.io.network.UdpManager;
import org.mobicents.media.server.io.network.channel.MultiplexedChannel;
import org.mobicents.media.server.utils.Text;

/**
 * Channel for exchanging RTCP traffic
 * 
 * @author Henrique Rosa (henrique.rosa@telestax.com)
 * 
 */
public class RtcpChannel extends MultiplexedChannel {

	private static final Logger logger = Logger.getLogger(RtcpChannel.class);

	// Core elements
	private final UdpManager udpManager;

	// Channel attribute
	private int channelId;
	private SocketAddress remotePeer;
	private boolean bound;

	// Statistics
	private final RtpStatistics statistics;

	// Protocol handler pipeline
	private RtcpHandler rtcpHandler;
	private DtlsHandler dtlsHandler;
	private StunHandler stunHandler;
	
	// WebRTC
	private boolean secure;

	public RtcpChannel(int channelId, RtpStatistics statistics, UdpManager udpManager) {
		// Initialize MultiplexedChannel elements
		super();

		// Core elements
		this.udpManager = udpManager;

		// Channel attributes
		this.channelId = channelId;
		this.remotePeer = null;
		this.bound = false;

		// Statistics
		this.statistics = statistics;

		// Protocol Handler pipeline
		this.rtcpHandler = new RtcpHandler(statistics);
		
		// WebRTC
		this.secure = false;
	}
	
	public int getLocalPort() {
		return this.channel != null ? this.channel.socket().getLocalPort() : 0;
	}

	public void setRemotePeer(SocketAddress remotePeer) {
		this.remotePeer = remotePeer;

		if (this.channel != null && this.channel.isConnected()) {
			try {
				this.channel.disconnect();
			} catch (IOException e) {
				logger.error(e.getMessage(), e);
			}

			boolean connectNow = this.udpManager
					.connectImmediately((InetSocketAddress) remotePeer);
			if (connectNow) {
				try {
					this.channel.connect(remotePeer);
				} catch (IOException e) {
					logger.info("Can not connect to remote address , please check that you are not using local address - 127.0.0.X to connect to remote");
					logger.error(e.getMessage(), e);
				}
			}
		}
	}
	
	public boolean isAvailable() {
		// The channel is available is is connected
		boolean available = this.channel != null && this.channel.isConnected();
		// In case of WebRTC calls the DTLS handshake must be completed
		if(this.secure) {
			available = available && this.dtlsHandler.isHandshakeComplete();
		}
		return available;
	}

	public boolean isBound() {
		return bound;
	}

	private void onBinding() {
		// Protocol Handler pipeline
		this.rtcpHandler.setChannel(this.channel);
		this.handlers.addHandler(this.rtcpHandler);
		this.rtcpHandler.joinRtpSession();
		
		if(this.secure) {
			this.dtlsHandler.setChannel(this.channel);
			this.stunHandler.setChannel(this.channel);
			this.handlers.addHandler(this.stunHandler);
		}
	}

	/**
	 * Binds the channel to an address and port
	 * 
	 * @param isLocal
	 *            whether the connection is local or not
	 * @param port
	 *            The RTCP port. Usually the RTP channel gets the even port and
	 *            RTCP channel get the next port.
	 * @throws SocketException
	 *             When the channel cannot be openend or bound
	 */
	public void bind(boolean isLocal, int port) throws SocketException {
		try {
			// Open this channel with UDP Manager on first available address
			this.channel = (DatagramChannel) udpManager.open(this).channel();
		} catch (IOException e) {
			throw new SocketException(e.getMessage());
		}

		// activate media elements
		onBinding();

		// bind data channel
		this.udpManager.bind(this.channel, port, isLocal);
		this.bound = true;
	}

	public void bind(DatagramChannel channel) throws SocketException {
		// External channel must be bound already
		if (!channel.socket().isBound()) {
			throw new SocketException("Datagram channel is not bound!");
		}

		try {
			// Register the channel on UDP Manager
			this.channel = (DatagramChannel) udpManager.open(channel, this).channel();
		} catch (IOException e) {
			throw new SocketException(e.getMessage());
		}

		// activate media elements
		onBinding();
		this.bound = true;
	}
	
	/**
	 * Checks whether the channel is secure or not.
	 * 
	 * @return Whether the channel handles regular RTCP traffic or SRTCP (secure).
	 */
	public boolean isSecure() {
		return secure;
	}
	
	public void enableSRTCP(Text remotePeerFingerprint, IceAuthenticator authenticator) {
		this.secure = true;
		
		// setup the DTLS handler
		if(this.dtlsHandler == null) {
			this.dtlsHandler = new DtlsHandler(this.channel);
		}
		this.dtlsHandler.setRemoteFingerprint(remotePeerFingerprint);
		
		// setup the SRTCP handler
		this.rtcpHandler.enableSRTCP(this.dtlsHandler);

		// setup the STUN handler
		if (this.stunHandler == null) {
			this.stunHandler = new StunHandler(authenticator);
		}
		this.handlers.addHandler(stunHandler);
	}
	
	public Text getDtlsLocalFingerprint() {
		if(this.secure) {
			return this.dtlsHandler.getLocalFingerprint();
		}
		return new Text("");
	}
	
	
	@Override
	public void receive() throws IOException {
		// Make sure the DTLS handshake is complete for WebRTC calls
		if(this.secure && !this.dtlsHandler.isHandshakeComplete()) {
			// TODO Need to implement own DTLS handler and drop bouncy castle implementation!
			if(!this.dtlsHandler.isHandshaking()) {
				this.dtlsHandler.handshake();
			}
		} else {
			// Receive traffic normally through the multiplexed channel
			super.receive();
		}
	}
	
	@Override
	public void close() {
		/*
		 * Instruct the RTCP handler to leave the RTP session.
		 * 
		 * This will result in scheduling an RTCP BYE to be sent. Since the BYE
		 * is not sent right away, the datagram channel can only be closed once
		 * the BYE has been sent. So, the handler is responsible for closing the
		 * channel.
		 */
		this.rtcpHandler.leaveRtpSession();
	}

}
