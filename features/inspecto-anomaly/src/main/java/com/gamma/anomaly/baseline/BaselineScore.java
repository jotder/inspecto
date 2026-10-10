package com.gamma.anomaly.baseline;

/**
 * The z pair of one entity and feature, with the D-AD12 basis flag: when the self baseline is insufficient but a
 * peer baseline exists, the entity is scored against peers only and {@code peersOnly} is true. When neither exists,
 * {@code insufficient} is true and the feature contributes 0 (design §4.2).
 */
public record BaselineScore(double observed, Baseline self, Baseline peer, double zSelf, double zPeer,
                            boolean peersOnly, boolean insufficient) {

    /**
     * @param peer null when the model declares no peers
     * @param unit the feature's floor unit (design §4.2 default 1)
     */
    public static BaselineScore of(double observed, Baseline self, Baseline peer, double unit) {
        boolean peerOk = peer != null && !peer.insufficient();
        double zp = peerOk ? peer.z(observed, unit) : Double.NaN;
        return new BaselineScore(observed, self, peer, self.z(observed, unit), zp,
                self.insufficient() && peerOk, self.insufficient() && !peerOk);
    }
}
