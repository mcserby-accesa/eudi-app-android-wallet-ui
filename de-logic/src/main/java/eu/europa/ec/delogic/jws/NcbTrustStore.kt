/*
 * Accesa fork — Digital Euro extensions to the EUDI reference wallet.
 * SPDX-License-Identifier: EUPL-1.2
 *
 * Bundled Eurosystem trust anchor for offline DE token verification.
 * Per ADR 0003, the ECB is the root of the Eurosystem trust hierarchy;
 * NCB leaf certs chain to that root. The wallet is government-distributed
 * (think browser shipping with CA roots), so we bundle the root at
 * compile time rather than fetching it.
 *
 * For the workshop demo this is a single self-issued root distributed
 * out of band; production would carry the actual ECB root the same way
 * a browser ships Mozilla's root store.
 */

package eu.europa.ec.delogic.jws

import androidx.annotation.RawRes
import eu.europa.ec.resourceslogic.R
import eu.europa.ec.resourceslogic.provider.ResourceProvider
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/** Source of NCB / Eurosystem root certificates for token-chain validation. */
interface NcbTrustStore {
    /**
     * Returns the trust anchors the wallet will pin against for incoming
     * `de-offline+jwt` token chains. An empty set means the trust anchor
     * is not yet configured — the verifier should fail closed.
     */
    fun trustAnchors(): Set<X509Certificate>
}

class BundledNcbTrustStore(
    private val resourceProvider: ResourceProvider,
    @param:RawRes private val rawResId: Int = R.raw.workshop_ncb_root,
) : NcbTrustStore {

    // Workshop trust root rarely changes; cache after the first parse.
    private val cached: Set<X509Certificate> by lazy(LazyThreadSafetyMode.PUBLICATION) {
        loadCerts()
    }

    override fun trustAnchors(): Set<X509Certificate> = cached

    private fun loadCerts(): Set<X509Certificate> {
        val raw = runCatching { resourceProvider.getStringFromRaw(rawResId) }
            .getOrNull()
            ?: return emptySet()

        // CertificateFactory.generateCertificates() handles the leading
        // `# …` comments cleanly — it scans for BEGIN CERTIFICATE markers
        // and ignores everything outside them. An entirely comment-only
        // file (the slice-4 placeholder) returns an empty collection.
        val factory = CertificateFactory.getInstance("X.509")
        return runCatching {
            factory.generateCertificates(raw.byteInputStream(Charsets.UTF_8))
                .filterIsInstance<X509Certificate>()
                .toSet()
        }.getOrElse { emptySet() }
    }
}
