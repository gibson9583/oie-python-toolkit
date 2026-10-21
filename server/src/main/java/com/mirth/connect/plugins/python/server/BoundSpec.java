/* SPDX-License-Identifier: MPL-2.0 */
package com.mirth.connect.plugins.python.server;

/** One registered Python script of a deployment: its exact source and environment. */
public final class BoundSpec {

    public final String digest;
    public final String role;
    public final int metaDataId;
    public final String connectorName;
    public final String label;
    public final String source;
    public final String profileId;
    public final String profileName;
    public final long profileRevision;
    public final String lockDigest;
    public final ArtifactStore.Lease artifact;
    public final int timeoutMillis;

    public BoundSpec(String digest, String role, int metaDataId, String connectorName, String label, String source, String profileId,
            String profileName, long profileRevision, String lockDigest, ArtifactStore.Lease artifact, int timeoutMillis) {
        this.digest = digest;
        this.role = role;
        this.metaDataId = metaDataId;
        this.connectorName = connectorName;
        this.label = label;
        this.source = source;
        this.profileId = profileId;
        this.profileName = profileName;
        this.profileRevision = profileRevision;
        this.lockDigest = lockDigest;
        this.artifact = artifact;
        this.timeoutMillis = timeoutMillis;
    }

    public static String key(int metaDataId, String role, String digest) {
        return metaDataId + "|" + role + "|" + digest;
    }

    public String key() {
        return key(metaDataId, role, digest);
    }
}
