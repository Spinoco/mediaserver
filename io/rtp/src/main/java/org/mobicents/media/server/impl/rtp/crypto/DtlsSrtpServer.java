/*
 * TeleStax, Open Source Cloud Communications
 * Copyright 2011-2014, Telestax Inc and individual contributors
 * by the @authors tag.
 *
 * This program is free software: you can redistribute it and/or modify
 * under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation; either version 3 of
 * the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>
 *
 */

package org.mobicents.media.server.impl.rtp.crypto;

import java.io.IOException;
import java.util.Hashtable;
import java.util.Vector;

import io.netty.util.internal.StringUtil;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.Logger;
import org.bouncycastle.asn1.x509.Certificate;
import org.bouncycastle.crypto.tls.*;
import org.bouncycastle.util.Arrays;

/**
 * 
 * This class represents the DTLS SRTP server connection handler.
 * 
 * The implementation follows the advise from Pierrick Grasland and Tim Panton on this forum thread:
 * http://bouncy-castle.1462172.n4.nabble.com/DTLS-SRTP-with-bouncycastle-1-49-td4656286.html
 * 
 * 
 * @author Ivelin Ivanov (ivelin.ivanov@telestax.com)
 * @author Henrique Rosa (henrique.rosa@telestax.com)
 *
 */
public class DtlsSrtpServer extends DefaultTlsServer {
	
    private static final Logger LOGGER = org.apache.logging.log4j.LogManager.getLogger(DtlsSrtpServer.class);

    // Certificate resources
//	private static final String[] CERT_RESOURCES = new String[] { "x509-server.pem", "x509-ca.pem" };
//	private static final String KEY_RESOURCE = "x509-server-key.pem";

    private static final String[] CERT_RESOURCES = new String[] { "x509-server-ecdsa.pem" };
    private static final String KEY_RESOURCE = "x509-server-key-ecdsa.pem";
	
	private String hashFunction = "";
    
    // the server response to the client handshake request
    // http://tools.ietf.org/html/rfc5764#section-4.1.1
	private UseSRTPData serverSrtpData;

	// Asymmetric shared keys derived from the DTLS handshake and used for the SRTP encryption/
	private byte[] srtpMasterClientKey;
	private byte[] srtpMasterServerKey;
	private byte[] srtpMasterClientSalt;
	private byte[] srtpMasterServerSalt;

	// Policies
	private SRTPPolicy srtpPolicy;
	private SRTPPolicy srtcpPolicy;
	
	public void notifyAlertRaised(short alertLevel, short alertDescription, String message, Exception cause) {
    	Level logLevel = (alertLevel == AlertLevel.fatal) ? Level.ERROR : Level.WARN; 
        LOGGER.log(logLevel, String.format("DTLS server raised alert (AlertLevel.%d, AlertDescription.%d, message='%s')", alertLevel, alertDescription, message), cause);
    }

    public void notifyAlertReceived(short alertLevel, short alertDescription) {
    	Level logLevel = (alertLevel == AlertLevel.fatal) ? Level.ERROR : Level.WARN; 
        LOGGER.log(logLevel, String.format("DTLS server received alert (AlertLevel.%d, AlertDescription.%d)", alertLevel, alertDescription));
    }


   @Override
   protected int[] getCipherSuites() {
          return new int[] { CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256 };
      }

      @Override
   public int getSelectedCipherSuite() throws IOException {
           /*
        * TODO RFC 5246 7.4.3. In order to negotiate correctly, the server MUST check any candidate cipher suites against the
        * "signature_algorithms" extension before selecting them. This is somewhat inelegant but is a compromise designed to
        * minimize changes to the original cipher suite design.
        */       /*
        * RFC 4429 5.1. A server that receives a ClientHello containing one or both of these extensions MUST use the client's
        * enumerated capabilities to guide its selection of an appropriate cipher suite. One of the proposed ECC cipher suites
        * must be negotiated only if the server can successfully complete the handshake while using the curves and point
        * formats supported by the client [...].
        */
        boolean eccCipherSuitesEnabled = supportsClientECCCapabilities(this.namedCurves, this.clientECPointFormats);
             int[] cipherSuites = getCipherSuites();
              for (int i = 0; i < cipherSuites.length; ++i) {
              int cipherSuite = cipherSuites[i];

              if (Arrays.contains(this.offeredCipherSuites, cipherSuite)
                            && (eccCipherSuitesEnabled || !TlsECCUtils.isECCCipherSuite(cipherSuite))
                            && org.bouncycastle.crypto.tls.TlsUtils.isValidCipherSuiteForVersion(cipherSuite, serverVersion)) {
                    return this.selectedCipherSuite = cipherSuite;
                }
            }
              throw new TlsFatalAlert(AlertDescription.handshake_failure);
    }

    public CertificateRequest getCertificateRequest() {
		Vector<SignatureAndHashAlgorithm> serverSigAlgs = null;
		if (org.bouncycastle.crypto.tls.TlsUtils.isSignatureAlgorithmsExtensionAllowed(serverVersion)) {
			short[] hashAlgorithms = new short[] { HashAlgorithm.sha512, HashAlgorithm.sha384, HashAlgorithm.sha256, HashAlgorithm.sha224, HashAlgorithm.sha1 };
			short[] signatureAlgorithms = new short[] { SignatureAlgorithm.rsa, SignatureAlgorithm.ecdsa };

			serverSigAlgs = new Vector<SignatureAndHashAlgorithm>();
			for (int i = 0; i < hashAlgorithms.length; ++i) {
				for (int j = 0; j < signatureAlgorithms.length; ++j) {
					serverSigAlgs.addElement(new SignatureAndHashAlgorithm(hashAlgorithms[i], signatureAlgorithms[j]));
				}
			}
		}
        return new CertificateRequest(new short[] { ClientCertificateType.ecdsa_sign }, serverSigAlgs, null);
    }

     @Override
    protected TlsSignerCredentials getECDSASignerCredentials() throws IOException {
       return TlsUtils.loadSignerCredentials(context, CERT_RESOURCES, KEY_RESOURCE, new SignatureAndHashAlgorithm(HashAlgorithm.sha256, SignatureAlgorithm.ecdsa));
   }

    public void notifyClientCertificate(org.bouncycastle.crypto.tls.Certificate clientCertificate) throws IOException {
        Certificate[] chain = clientCertificate.getCertificateList();
        LOGGER.info(String.format("Received client certificate chain of length %d", chain.length));
        
        for (int i = 0; i != chain.length; i++) {
            Certificate entry = chain[i];
            LOGGER.info(String.format("WebRTC Client certificate fingerprint:%s (%s)", TlsUtils.fingerprint(this.hashFunction, entry), entry.getSubject()));
        }
    }

    protected ProtocolVersion getMaximumVersion() {
        return ProtocolVersion.DTLSv12;
    }

    protected ProtocolVersion getMinimumVersion() {
        return ProtocolVersion.DTLSv10;
    }

    protected TlsEncryptionCredentials getRSAEncryptionCredentials() throws IOException {
        return TlsUtils.loadEncryptionCredentials(context, CERT_RESOURCES, KEY_RESOURCE);
    }

    @SuppressWarnings("unchecked")
    protected TlsSignerCredentials getRSASignerCredentials() throws IOException {
    	/*
         * TODO Note that this code fails to provide default value for the client supported
         * algorithms if it wasn't sent.
         */
        SignatureAndHashAlgorithm signatureAndHashAlgorithm = null;
		Vector<SignatureAndHashAlgorithm> sigAlgs = supportedSignatureAlgorithms;
        if (sigAlgs != null) {
            for (int i = 0; i < sigAlgs.size(); ++i) {
                SignatureAndHashAlgorithm sigAlg = sigAlgs.elementAt(i);
                if (sigAlg.getSignature() == SignatureAlgorithm.rsa) {
                    signatureAndHashAlgorithm = sigAlg;
                    break;
                }
            }

            if (signatureAndHashAlgorithm == null) {
                return null;
            }
        }
        return TlsUtils.loadSignerCredentials(context, new String[]{"x509-server.pem", "x509-ca.pem"}, "x509-server-key.pem", signatureAndHashAlgorithm);
    }
    
    @SuppressWarnings("unchecked")
	@Override
    public Hashtable<Integer, byte[]> getServerExtensions() throws IOException {
    	Hashtable<Integer, byte[]> serverExtensions = (Hashtable<Integer, byte[]>) super.getServerExtensions();
        if (TlsSRTPUtils.getUseSRTPExtension(serverExtensions) == null) {
            if (serverExtensions == null) {
            	serverExtensions = new Hashtable<Integer, byte[]>();
            }
            TlsSRTPUtils.addUseSRTPExtension(serverExtensions, serverSrtpData );
        }
        return serverExtensions;
    }
    
    @SuppressWarnings("rawtypes")
	@Override
    public void processClientExtensions(Hashtable newClientExtensions) throws IOException {
    	super.processClientExtensions(newClientExtensions);
    	
    	// set to some reasonable default value
    	int chosenProfile = SRTPProtectionProfile.SRTP_AES128_CM_HMAC_SHA1_80;
    	UseSRTPData clientSrtpData = TlsSRTPUtils.getUseSRTPExtension(newClientExtensions);
    	
    	for (int profile : clientSrtpData.getProtectionProfiles()) {
    		switch (profile) {
    			case SRTPProtectionProfile.SRTP_AES128_CM_HMAC_SHA1_32:
    			case SRTPProtectionProfile.SRTP_AES128_CM_HMAC_SHA1_80:
    			case SRTPProtectionProfile.SRTP_NULL_HMAC_SHA1_32:
    			case SRTPProtectionProfile.SRTP_NULL_HMAC_SHA1_80:
    				chosenProfile  = profile;
    				break;
    			default:
    		}
    	}
    	
    	// server chooses a mutually supported SRTP protection profile
    	// http://tools.ietf.org/html/draft-ietf-avt-dtls-srtp-07#section-4.1.2
		int[] protectionProfiles = { chosenProfile };
    	
    	// server agrees to use the MKI offered by the client
    	serverSrtpData = new UseSRTPData(protectionProfiles, clientSrtpData.getMki());
    }
    
    public byte[] getKeyingMaterial(int length) {
        return context.exportKeyingMaterial(ExporterLabel.dtls_srtp, null, length);
    }

    /**
     * 
     * @return the shared secret key that will be used for the SRTP session
     */
    public void prepareSrtpSharedSecret() {
    	SRTPParameters srtpParams = SRTPParameters.getSrtpParametersForProfile(serverSrtpData.getProtectionProfiles()[0]);
    	final int keyLen = srtpParams.getCipherKeyLength();
    	final int saltLen = srtpParams.getCipherSaltLength();
    	
    	srtpPolicy = srtpParams.getSrtpPolicy();
    	srtcpPolicy = srtpParams.getSrtcpPolicy();
    	
        srtpMasterClientKey = new byte[keyLen];
        srtpMasterServerKey = new byte[keyLen];
        srtpMasterClientSalt = new byte[saltLen];
        srtpMasterServerSalt = new byte[saltLen];
        
        // 2* (key + salt lenght) / 8. From http://tools.ietf.org/html/rfc5764#section-4-2
        // No need to divide by 8 here since lengths are already in bits
        byte[] sharedSecret = getKeyingMaterial(2 * (keyLen + saltLen));
        
        /*
         * 
         * See: http://tools.ietf.org/html/rfc5764#section-4.2
         * 
         * sharedSecret is an equivalent of :
         * 
         * struct {
         *     client_write_SRTP_master_key[SRTPSecurityParams.master_key_len];
         *     server_write_SRTP_master_key[SRTPSecurityParams.master_key_len];
         *     client_write_SRTP_master_salt[SRTPSecurityParams.master_salt_len];
         *     server_write_SRTP_master_salt[SRTPSecurityParams.master_salt_len];
         *  } ;
         *
         * Here, client = local configuration, server = remote.
         * NOTE [ivelin]: 'local' makes sense if this code is used from a DTLS SRTP client. 
         *                Here we run as a server, so 'local' referring to the client is actually confusing. 
         * 
         * l(k) = KEY length
         * s(k) = salt lenght
         * 
         * So we have the following repartition :
         *                           l(k)                                 2*l(k)+s(k)   
         *                                                   2*l(k)                       2*(l(k)+s(k))
         * +------------------------+------------------------+---------------+-------------------+
         * + local key           |    remote key    | local salt   | remote salt   |
         * +------------------------+------------------------+---------------+-------------------+
         */
        System.arraycopy(sharedSecret, 0, srtpMasterClientKey, 0, keyLen); 
        System.arraycopy(sharedSecret, keyLen, srtpMasterServerKey, 0, keyLen);
        System.arraycopy(sharedSecret, 2*keyLen, srtpMasterClientSalt, 0, saltLen);
        System.arraycopy(sharedSecret, (2*keyLen+saltLen), srtpMasterServerSalt, 0, saltLen);
    }
    
    public SRTPPolicy getSrtpPolicy() {
    	return srtpPolicy;
    }
    
    public SRTPPolicy getSrtcpPolicy() {
    	return srtcpPolicy;
    }
    
    public byte[] getSrtpMasterServerKey() {
    	return srtpMasterServerKey;
    }
    
    public byte[] getSrtpMasterServerSalt() {
    	return srtpMasterServerSalt;
    }
    
    public byte[] getSrtpMasterClientKey() {
    	return srtpMasterClientKey;
    }
    
    public byte[] getSrtpMasterClientSalt() {
    	return srtpMasterClientSalt;
    }

    public byte[] getServerRandom() {
        return context.getSecurityParameters().getServerRandom();
    }

    public byte[] getClientRandom() {
        return context.getSecurityParameters().getClientRandom();
    }
    
	/**
	 * Gets the fingerprint of the Certificate associated to the server.
	 * 
	 * @return The fingerprint of the server certificate. Returns an empty
	 *         String if the server does not contain a certificate.
	 */
	public String generateFingerprint(String hashFunction) {
		try {
			this.hashFunction = hashFunction;
			org.bouncycastle.crypto.tls.Certificate chain = TlsUtils.loadCertificateChain(CERT_RESOURCES);
			Certificate certificate = chain.getCertificateAt(0);
			return TlsUtils.fingerprint(this.hashFunction, certificate);
		} catch (IOException e) {
			LOGGER.error("Could not get local fingerprint: "+ e.getMessage());
			return "";
		}
	}
    
}
