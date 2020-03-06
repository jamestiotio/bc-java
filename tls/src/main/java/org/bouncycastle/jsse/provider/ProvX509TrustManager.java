package org.bouncycastle.jsse.provider;

import java.net.Socket;
import java.security.GeneralSecurityException;
import java.security.InvalidAlgorithmParameterException;
import java.security.NoSuchAlgorithmException;
import java.security.Provider;
import java.security.cert.CertPath;
import java.security.cert.CertPathBuilder;
import java.security.cert.CertStore;
import java.security.cert.CertStoreParameters;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.CollectionCertStoreParameters;
import java.security.cert.PKIXBuilderParameters;
import java.security.cert.PKIXCertPathBuilderResult;
import java.security.cert.PKIXParameters;
import java.security.cert.TrustAnchor;
import java.security.cert.X509CertSelector;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.X509TrustManager;

import org.bouncycastle.asn1.x509.KeyPurposeId;
import org.bouncycastle.jcajce.util.JcaJceHelper;
import org.bouncycastle.jsse.BCExtendedSSLSession;
import org.bouncycastle.jsse.BCSNIHostName;
import org.bouncycastle.jsse.BCSNIServerName;
import org.bouncycastle.jsse.BCSSLParameters;
import org.bouncycastle.jsse.BCStandardConstants;
import org.bouncycastle.jsse.BCX509ExtendedTrustManager;
import org.bouncycastle.jsse.java.security.BCAlgorithmConstraints;

class ProvX509TrustManager
    extends BCX509ExtendedTrustManager
{
    private static final Logger LOG = Logger.getLogger(ProvX509TrustManager.class.getName());

    private static final boolean provCheckRevocation = PropertyUtils
        .getBooleanSystemProperty("com.sun.net.ssl.checkRevocation", false);

    private static final Map<String, Integer> serverKeyUsageMap = createServerKeyUsageMap();

    private static Map<String, Integer> createServerKeyUsageMap()
    {
        Map<String, Integer> kus = new HashMap<String, Integer>();

        kus.put("DHE_DSS", ProvAlgorithmChecker.KU_DIGITAL_SIGNATURE);
        kus.put("DHE_RSA", ProvAlgorithmChecker.KU_DIGITAL_SIGNATURE);
        kus.put("ECDHE_ECDSA", ProvAlgorithmChecker.KU_DIGITAL_SIGNATURE);
        kus.put("ECDHE_RSA", ProvAlgorithmChecker.KU_DIGITAL_SIGNATURE);
        kus.put("UNKNOWN", ProvAlgorithmChecker.KU_DIGITAL_SIGNATURE);  // TLS 1.3

        kus.put("RSA", ProvAlgorithmChecker.KU_KEY_ENCIPHERMENT);

        kus.put("DH_DSS", ProvAlgorithmChecker.KU_KEY_AGREEMENT);
        kus.put("DH_RSA", ProvAlgorithmChecker.KU_KEY_AGREEMENT);
        kus.put("ECDH_ECDSA", ProvAlgorithmChecker.KU_KEY_AGREEMENT);
        kus.put("ECDH_RSA", ProvAlgorithmChecker.KU_KEY_AGREEMENT);

        return Collections.unmodifiableMap(kus);
    }

    private final JcaJceHelper helper;
    private final Set<X509Certificate> trustedCerts;
    private final PKIXBuilderParameters baseParameters;
    private final X509TrustManager exportX509TrustManager;

    ProvX509TrustManager(JcaJceHelper helper, Set<TrustAnchor> trustAnchors)
        throws InvalidAlgorithmParameterException
    {
        this.helper = helper;
        this.trustedCerts = getTrustedCerts(trustAnchors);

        // Setup PKIX parameters
        if (trustedCerts.isEmpty())
        {
            this.baseParameters = null;
        }
        else
        {
            this.baseParameters = new PKIXBuilderParameters(trustAnchors, null);
            this.baseParameters.setRevocationEnabled(provCheckRevocation);
        }

        this.exportX509TrustManager = X509TrustManagerUtil.exportX509TrustManager(this);
    }

    ProvX509TrustManager(JcaJceHelper helper, PKIXParameters baseParameters)
        throws InvalidAlgorithmParameterException
    {
        this.helper = helper;
        this.trustedCerts = getTrustedCerts(baseParameters.getTrustAnchors());

        // Setup PKIX parameters
        if (trustedCerts.isEmpty())
        {
            this.baseParameters = null;
        }
        else if (baseParameters instanceof PKIXBuilderParameters)
        {
            this.baseParameters = (PKIXBuilderParameters)baseParameters.clone();
            this.baseParameters.setTargetCertConstraints(null);
        }
        else
        {
            this.baseParameters = new PKIXBuilderParameters(baseParameters.getTrustAnchors(), null);
            this.baseParameters.setAnyPolicyInhibited(baseParameters.isAnyPolicyInhibited());
            this.baseParameters.setCertPathCheckers(baseParameters.getCertPathCheckers());
            this.baseParameters.setCertStores(baseParameters.getCertStores());
            this.baseParameters.setDate(baseParameters.getDate());
            this.baseParameters.setExplicitPolicyRequired(baseParameters.isExplicitPolicyRequired());
            this.baseParameters.setInitialPolicies(baseParameters.getInitialPolicies());
            this.baseParameters.setPolicyMappingInhibited(baseParameters.isPolicyMappingInhibited());
            this.baseParameters.setPolicyQualifiersRejected(baseParameters.getPolicyQualifiersRejected());
            this.baseParameters.setRevocationEnabled(baseParameters.isRevocationEnabled());
            this.baseParameters.setSigProvider(baseParameters.getSigProvider());
        }

        this.exportX509TrustManager = X509TrustManagerUtil.exportX509TrustManager(this);
    }

    X509TrustManager getExportX509TrustManager()
    {
        return exportX509TrustManager;
    }

    public void checkClientTrusted(X509Certificate[] chain, String authType)
        throws CertificateException
    {
        checkTrusted(chain, authType, null, false);
    }

    public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket)
        throws CertificateException
    {
        checkTrusted(chain, authType, TransportData.from(socket), false);
    }

    public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine)
        throws CertificateException
    {
        checkTrusted(chain, authType, TransportData.from(engine), false);
    }

    public void checkServerTrusted(X509Certificate[] chain, String authType)
        throws CertificateException
    {
        checkTrusted(chain, authType, null, true);
    }

    public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket)
        throws CertificateException
    {
        checkTrusted(chain, authType, TransportData.from(socket), true);
    }

    public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine)
        throws CertificateException
    {
        checkTrusted(chain, authType, TransportData.from(engine), true);
    }

    public X509Certificate[] getAcceptedIssuers()
    {
        return trustedCerts.toArray(new X509Certificate[trustedCerts.size()]);
    }

    private X509Certificate[] buildCertPath(X509Certificate[] chain, BCAlgorithmConstraints algorithmConstraints)
        throws GeneralSecurityException
    {
        /*
         * RFC 8446 4.4.2 "For maximum compatibility, all implementations SHOULD be prepared to
         * handle potentially extraneous certificates and arbitrary orderings from any TLS version,
         * with the exception of the end-entity certificate which MUST be first."
         */
        X509Certificate eeCert = chain[0];
        if (trustedCerts.contains(eeCert))
        {
            return new X509Certificate[]{ eeCert };
        }

        /*
         * TODO[jsse] When 'checkServerTrusted' (only?), make use of any status responses (OCSP) via
         * BCExtendedSSLSession.getStatusResponses()
         */

        // TODO Can we cache the CertificateFactory instance?
        CertificateFactory certificateFactory = helper.createCertificateFactory("X.509");
        Provider pkixProvider = certificateFactory.getProvider();

        CertStoreParameters certStoreParameters = getCertStoreParameters(eeCert, chain);
        CertStore certStore;
        try
        {
            certStore = CertStore.getInstance("Collection", certStoreParameters, pkixProvider);
        }
        catch (GeneralSecurityException e)
        {
            certStore = CertStore.getInstance("Collection", certStoreParameters);
        }

        X509CertSelector certSelector = new X509CertSelector();
        certSelector.setCertificate(eeCert);

        PKIXBuilderParameters certPathParameters = (PKIXBuilderParameters)baseParameters.clone();
        certPathParameters.addCertPathChecker(new ProvAlgorithmChecker(helper, algorithmConstraints));
        certPathParameters.addCertStore(certStore);
        certPathParameters.setTargetCertConstraints(certSelector);

        CertPathBuilder builder;
        try
        {
            builder = CertPathBuilder.getInstance("PKIX", pkixProvider);
        }
        catch (NoSuchAlgorithmException e)
        {
            builder = CertPathBuilder.getInstance("PKIX");
        }

        PKIXCertPathBuilderResult result = (PKIXCertPathBuilderResult)builder.build(certPathParameters);

        /*
         * TODO[jsse] Determine 'chainsToPublicCA' based on the trust anchor for the result
         * chain. SunJSSE appears to consider this to be any trusted cert in original-location
         * cacerts file with alias.contains(" [jdk")
         */
        return getTrustedChain(result.getCertPath(), result.getTrustAnchor());
    }

    private void checkTrusted(X509Certificate[] chain, String authType, TransportData transportData,
        boolean checkServerTrusted) throws CertificateException
    {
        if (null == chain || chain.length < 1)
        {
            throw new IllegalArgumentException("'chain' must be a chain of at least one certificate");
        }
        if (null == authType || authType.length() < 1)
        {
            throw new IllegalArgumentException("'authType' must be a non-null, non-empty string");
        }

        if (null == baseParameters)
        {
            throw new CertificateException("Unable to build a CertPath: no PKIXBuilderParameters available");
        }

        X509Certificate[] trustedChain = validateChain(chain, authType, transportData, checkServerTrusted);

        checkExtendedTrust(trustedChain, authType, transportData, checkServerTrusted);
    }

    // NOTE: We avoid re-reading eeCert from chain[0]
    private CertStoreParameters getCertStoreParameters(X509Certificate eeCert, X509Certificate[] chain)
    {
        ArrayList<X509Certificate> certs = new ArrayList<X509Certificate>(chain.length);
        certs.add(eeCert);
        for (int i = 1; i < chain.length; ++i)
        {
            if (!trustedCerts.contains(chain[i]))
            {
                certs.add(chain[i]);
            }
        }
        return new CollectionCertStoreParameters(Collections.unmodifiableCollection(certs));
    }

    private X509Certificate[] validateChain(X509Certificate[] chain, String authType, TransportData transportData,
        boolean checkServerTrusted) throws CertificateException
    {
        try
        {
            BCAlgorithmConstraints algorithmConstraints = TransportData.getAlgorithmConstraints(transportData, false);

            X509Certificate[] trustedChain = buildCertPath(chain, algorithmConstraints);

            KeyPurposeId ekuOID = getRequiredExtendedKeyUsage(checkServerTrusted);
            int kuBit = getRequiredKeyUsage(checkServerTrusted, authType);

            ProvAlgorithmChecker.checkCertPathExtras(helper, algorithmConstraints, trustedChain, ekuOID, kuBit);

            // TODO[jsse] Consider supporting jdk.security.caDistrustPolicies security property

            return trustedChain;
        }
        catch (GeneralSecurityException e)
        {
            throw new CertificateException("Unable to construct a valid chain", e);
        }
    }

    static void checkExtendedTrust(X509Certificate[] trustedChain, String authType, TransportData transportData,
        boolean checkServerTrusted) throws CertificateException
    {
        if (null != transportData)
        {
            BCSSLParameters parameters = transportData.getParameters();

            String endpointIDAlg = parameters.getEndpointIdentificationAlgorithm();
            if (null != endpointIDAlg && endpointIDAlg.length() > 0)
            {
                BCExtendedSSLSession handshakeSession = transportData.getHandshakeSession();
                if (null == handshakeSession)
                {
                    throw new CertificateException("No handshake session");
                }

                checkEndpointID(trustedChain[0], endpointIDAlg, checkServerTrusted, handshakeSession);
            }
        }
    }

    static KeyPurposeId getRequiredExtendedKeyUsage(boolean checkServerTrusted)
    {
        return checkServerTrusted
            ?   KeyPurposeId.id_kp_serverAuth
            :   KeyPurposeId.id_kp_clientAuth;
    }

    static int getRequiredKeyUsage(boolean checkServerTrusted, String authType)
        throws CertificateException
    {
        if (!checkServerTrusted)
        {
            return ProvAlgorithmChecker.KU_DIGITAL_SIGNATURE;
        }

        Integer requiredKeyUsage = serverKeyUsageMap.get(authType);
        if (null == requiredKeyUsage)
        {
            throw new CertificateException("Unsupported server authType: " + authType);
        }

        return requiredKeyUsage.intValue();
    }

    private static void checkEndpointID(X509Certificate certificate, String endpointIDAlg, boolean checkServerTrusted,
        BCExtendedSSLSession sslSession) throws CertificateException
    {
        String peerHost = sslSession.getPeerHost();
        if (checkServerTrusted)
        {
            BCSNIHostName sniHostName = getSNIHostName(sslSession);
            if (null != sniHostName)
            {
                String hostname = sniHostName.getAsciiName();
                if (!hostname.equalsIgnoreCase(peerHost))
                {
                    try
                    {
                        checkEndpointID(hostname, certificate, endpointIDAlg);
                        return;
                    }
                    catch (CertificateException e)
                    {
                        // ignore (log only) and continue on to check 'peerHost' instead
                        LOG.log(Level.FINE, "Server's endpoint ID did not match the SNI host_name: " + hostname, e);
                    }
                }
            }
        }

        checkEndpointID(peerHost, certificate, endpointIDAlg);
    }

    private static void checkEndpointID(String hostname, X509Certificate certificate, String endpointIDAlg)
        throws CertificateException
    {
        // Strip "[]" off IPv6 addresses
        hostname = JsseUtils.stripSquareBrackets(hostname);

        if (endpointIDAlg.equalsIgnoreCase("HTTPS"))
        {
            HostnameUtil.checkHostname(hostname, certificate, true);
        }
        else if (endpointIDAlg.equalsIgnoreCase("LDAP") || endpointIDAlg.equalsIgnoreCase("LDAPS"))
        {
            HostnameUtil.checkHostname(hostname, certificate, false);
        }
        else
        {
            throw new CertificateException("Unknown endpoint ID algorithm: " + endpointIDAlg);
        }
    }

    private static BCSNIHostName getSNIHostName(BCExtendedSSLSession sslSession)
    {
        List<BCSNIServerName> serverNames = sslSession.getRequestedServerNames();
        if (null != serverNames)
        {
            for (BCSNIServerName serverName : serverNames)
            {
                if (null != serverName && BCStandardConstants.SNI_HOST_NAME == serverName.getType())
                {
                    if (serverName instanceof BCSNIHostName)
                    {
                        return (BCSNIHostName)serverName;
                    }

                    try
                    {
                        return new BCSNIHostName(serverName.getEncoded());
                    }
                    catch (RuntimeException e)
                    {
                        return null;
                    }
                }
            }
        }
        return null;
    }

    private static X509Certificate getTrustedCert(TrustAnchor trustAnchor) throws CertificateException
    {
        X509Certificate trustedCert = trustAnchor.getTrustedCert();
        if (null == trustedCert)
        {
            throw new CertificateException("No certificate for TrustAnchor");
        }
        return trustedCert;
    }

    private static Set<X509Certificate> getTrustedCerts(Set<TrustAnchor> trustAnchors)
    {
        Set<X509Certificate> result = new HashSet<X509Certificate>(trustAnchors.size());
        for (TrustAnchor trustAnchor : trustAnchors)
        {
            if (null != trustAnchor)
            {
                X509Certificate trustedCert = trustAnchor.getTrustedCert();
                if (null != trustedCert)
                {
                    result.add(trustedCert);
                }
            }
        }
        return result;
    }

    private static X509Certificate[] getTrustedChain(CertPath certPath, TrustAnchor trustAnchor)
        throws CertificateException
    {
        List<? extends Certificate> certificates = certPath.getCertificates();
        X509Certificate[] result = new X509Certificate[certificates.size() + 1];
        certificates.toArray(result);
        result[result.length - 1] = getTrustedCert(trustAnchor);
        return result;
    }
}
