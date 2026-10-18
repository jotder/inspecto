package com.gamma.analytics.model;

import com.gamma.analytics.score.EntityCap;
import com.gamma.graph.RunControl;
import java.util.ArrayList;
import java.util.List;

/**
 * Isolation forest (ANALYTICS-CORE-1 C4; Liu, Ting &amp; Zhou 2008), hand-written and <b>deterministic</b>: the same
 * points in the same order and the same {@link Spec} (seed included) always give the same forest and the same scores,
 * to the last bit (a pinned {@link SeededRandom}, sequential arithmetic). The model is the forest itself, rebuilt on
 * every run — it is unsupervised and cheap, so there is no fitted artefact to version; the {@code seed} and the
 * parameters are what a run records. Fitting is a Job / offline concern (W7).
 *
 * <p>The contract:
 * <ul>
 *   <li><b>Trees.</b> {@link Spec#trees()} (default {@value #DEFAULT_TREES}) trees, each over a sample of
 *       {@code ψ = min(sampleSize, n)} points drawn without replacement (default sample {@value #DEFAULT_SAMPLE_SIZE});
 *       height limit {@code ⌈log₂ ψ⌉}. A node splits on a feature chosen among those that still vary in the node —
 *       with probability proportional to {@code featureWeights} (uniform when null) — at a value drawn uniformly
 *       between the node's minimum and maximum.</li>
 *   <li><b>Path length</b> {@code h(x)} is the depth reached plus {@code c(size)} for the leaf's unresolved points,
 *       {@code c(m) = 2·H(m−1) − 2(m−1)/m} (H the harmonic number, {@code ln i + 0.5772156649}; {@code c(1) = 0},
 *       {@code c(2) = 1}).</li>
 *   <li><b>Score</b> {@code s = 2^(−E[h] / c(ψ))} ∈ (0, 1]: about 0.5 for a typical point, towards 1 for an easily
 *       isolated one. {@link Path#excess()} is {@code 2·max(0, s − 0.5)} ∈ [0, 1], the part above typical.</li>
 *   <li><b>Per-feature explanation (counterfactual).</b> {@link #attribute} asks, for each feature, how much of the
 *       point's {@link Path#excess() excess} disappears if that one feature took the caller's <i>reference</i> value (for
 *       the Anomaly Score: no deviation, 0) — the drops, clipped at 0 and normalised to sum 1. Interactions mean the drops
 *       need not add up to the excess, hence the normalisation; when no single feature changes the score the shares are
 *       equal over the features that differ from the reference. Split counts along the path were tried first and
 *       rejected: they credit a feature for being chosen early by chance, not for isolating the point.</li>
 *   <li><b>Bounded input (D-AD10).</b> more than {@link Spec#maxPoints()} rows fails ({@link InputCap.Exceeded}).</li>
 *   <li><b>Cancellation.</b> {@link RunControl#checkpoint()} once per tree.</li>
 * </ul>
 * At least two points are needed (a lone point has nothing to be isolated from).
 */
public final class IsolationForest {

    public static final int DEFAULT_TREES = 100;
    public static final int MAX_TREES = 1_000;
    public static final int DEFAULT_SAMPLE_SIZE = 256;
    public static final int MAX_SAMPLE_SIZE = 4_096;
    /** The most feature columns one forest takes. */
    public static final int MAX_FEATURES = 50;
    private static final double EULER = 0.5772156649015329;

    private final int psi;
    private final int features;
    private final List<Tree> trees;

    private IsolationForest(int psi, int features, List<Tree> trees) {
        this.psi = psi;
        this.features = features;
        this.trees = trees;
    }

    /** What to fit; {@code maxPoints} is the caller's entity cap. */
    public record Spec(int trees, int sampleSize, long seed, int maxPoints) {
        public Spec {
            if (trees < 1 || trees > MAX_TREES) throw new IllegalArgumentException("trees must be within 1.." + MAX_TREES + ": " + trees);
            if (sampleSize < 2 || sampleSize > MAX_SAMPLE_SIZE)
                throw new IllegalArgumentException("sampleSize must be within 2.." + MAX_SAMPLE_SIZE + ": " + sampleSize);
            InputCap.resolve(maxPoints);
        }

        public static Spec of(long seed) {
            return new Spec(DEFAULT_TREES, DEFAULT_SAMPLE_SIZE, seed, EntityCap.DEFAULT);
        }
    }

    /** One point's isolation: the mean path length over the trees, the anomaly score {@code s}, and its {@link #excess()}. */
    public record Path(double meanPathLength, double score) {
        /** {@code 2·max(0, s − 0.5)}: 0 for a typical point, 1 for a certain outlier. */
        public double excess() {
            return 2.0 * Math.max(0.0, score - 0.5);
        }
    }

    /** One tree in parallel arrays; a leaf has {@code feature < 0} and {@code size} = the points that reached it. */
    private static final class Tree {
        int[] feature = new int[16], left = new int[16], right = new int[16], size = new int[16];
        double[] split = new double[16];
        int count;

        int add(int feat, double at, int leafSize) {
            if (count == feature.length) {
                int n = count * 2;
                feature = java.util.Arrays.copyOf(feature, n);
                left = java.util.Arrays.copyOf(left, n);
                right = java.util.Arrays.copyOf(right, n);
                size = java.util.Arrays.copyOf(size, n);
                split = java.util.Arrays.copyOf(split, n);
            }
            feature[count] = feat;
            split[count] = at;
            size[count] = leafSize;
            return count++;
        }
    }

    /** Fit on {@code points} (rows × features, finite), in a stable order — see the class contract. */
    public static IsolationForest fit(double[][] points, double[] featureWeights, Spec spec, RunControl control) {
        int n = points.length;
        if (n < 2) throw new IllegalArgumentException("an isolation forest needs at least 2 points: " + n);
        InputCap.require("isolation forest input", n, spec.maxPoints());
        int d = points[0].length;
        if (d < 1 || d > MAX_FEATURES) throw new IllegalArgumentException("an isolation forest takes 1.." + MAX_FEATURES + " features: " + d);
        for (double[] p : points) {
            if (p.length != d) throw new IllegalArgumentException("every point needs " + d + " values");
            for (double v : p)
                if (!Double.isFinite(v)) throw new IllegalArgumentException("a NaN or infinite value: drop or fill missing values first");
        }
        double[] w = featureWeights;
        if (w == null) {
            w = new double[d];
            java.util.Arrays.fill(w, 1.0);
        } else {
            if (w.length != d) throw new IllegalArgumentException("featureWeights has " + w.length + " values, the points " + d);
            for (double v : w) if (!(v > 0) || !Double.isFinite(v)) throw new IllegalArgumentException("feature weights must be positive: " + v);
        }
        int psi = Math.min(spec.sampleSize(), n);
        int heightLimit = (int) Math.ceil(Math.log(psi) / Math.log(2));
        SeededRandom rnd = new SeededRandom(spec.seed());
        List<Tree> trees = new ArrayList<>(spec.trees());
        int[] pool = new int[n];
        for (int i = 0; i < n; i++) pool[i] = i;
        for (int t = 0; t < spec.trees(); t++) {
            control.checkpoint();
            // partial Fisher-Yates: the first psi slots are a sample without replacement
            for (int i = 0; i < psi; i++) {
                int j = i + rnd.nextInt(n - i);
                int tmp = pool[i];
                pool[i] = pool[j];
                pool[j] = tmp;
            }
            int[] sample = java.util.Arrays.copyOf(pool, psi);
            Tree tree = new Tree();
            build(tree, points, sample, 0, heightLimit, w, rnd);
            trees.add(tree);
            control.progress(t + 1, spec.trees());
        }
        return new IsolationForest(psi, d, trees);
    }

    private static int build(Tree tree, double[][] points, int[] idx, int depth, int heightLimit, double[] w, SeededRandom rnd) {
        if (depth >= heightLimit || idx.length <= 1) return tree.add(-1, 0, idx.length);
        int d = w.length;
        double[] lo = new double[d], hi = new double[d];
        for (int j = 0; j < d; j++) {
            lo[j] = Double.POSITIVE_INFINITY;
            hi[j] = Double.NEGATIVE_INFINITY;
        }
        for (int i : idx)
            for (int j = 0; j < d; j++) {
                lo[j] = Math.min(lo[j], points[i][j]);
                hi[j] = Math.max(hi[j], points[i][j]);
            }
        double total = 0;
        for (int j = 0; j < d; j++) if (lo[j] < hi[j]) total += w[j];
        if (total <= 0) return tree.add(-1, 0, idx.length);   // every point identical: nothing left to split on
        double target = rnd.nextDouble() * total, run = 0;
        int feature = -1;
        for (int j = 0; j < d; j++) {
            if (!(lo[j] < hi[j])) continue;
            run += w[j];
            feature = j;
            if (run > target) break;
        }
        double at = lo[feature] + rnd.nextDouble() * (hi[feature] - lo[feature]);   // in [lo, hi): both sides non-empty
        int nl = 0;
        for (int i : idx) if (points[i][feature] <= at) nl++;
        int[] l = new int[nl], r = new int[idx.length - nl];
        int a = 0, b = 0;
        for (int i : idx) if (points[i][feature] <= at) l[a++] = i; else r[b++] = i;
        int node = tree.add(feature, at, idx.length);
        // the children first: building them may grow (and so replace) the tree's arrays
        int leftChild = build(tree, points, l, depth + 1, heightLimit, w, rnd);
        int rightChild = build(tree, points, r, depth + 1, heightLimit, w, rnd);
        tree.left[node] = leftChild;
        tree.right[node] = rightChild;
        return node;
    }

    /** The isolation of {@code point} by this forest. */
    public Path path(double[] point) {
        if (point.length != features) throw new IllegalArgumentException("a point has " + point.length + " values, the forest " + features);
        for (double v : point) if (Double.isNaN(v)) throw new IllegalArgumentException("a NaN value: drop or fill missing values first");
        double sum = 0;
        for (Tree t : trees) {
            int node = 0, depth = 0;
            while (t.feature[node] >= 0) {
                node = point[t.feature[node]] <= t.split[node] ? t.left[node] : t.right[node];
                depth++;
            }
            sum += depth + averagePath(t.size[node]);
        }
        double mean = sum / trees.size();
        return new Path(mean, Math.pow(2.0, -mean / averagePath(psi)));
    }

    /**
     * The per-feature shares (sum 1, or all 0 when the point has no excess) of {@code point}'s isolation — see the class
     * contract. {@code reference} is the value each feature is set to in turn.
     */
    public double[] attribute(double[] point, double[] reference) {
        if (reference.length != features) throw new IllegalArgumentException("reference has " + reference.length + " values, the forest " + features);
        double excess = path(point).excess();
        double[] shares = new double[features];
        if (!(excess > 0)) return shares;
        double[] probe = point.clone();
        double total = 0;
        for (int j = 0; j < features; j++) {
            if (point[j] == reference[j]) continue;
            probe[j] = reference[j];
            shares[j] = Math.max(0.0, excess - path(probe).excess());
            probe[j] = point[j];
            total += shares[j];
        }
        if (total > 0) {
            for (int j = 0; j < features; j++) shares[j] /= total;
            return shares;
        }
        int differing = 0;
        for (int j = 0; j < features; j++) if (point[j] != reference[j]) differing++;
        for (int j = 0; j < features; j++) shares[j] = differing > 0 ? (point[j] != reference[j] ? 1.0 / differing : 0.0) : 1.0 / features;
        return shares;
    }

    /** {@code c(m)}: the average path length of an unsuccessful search in a binary search tree of {@code m} points. */
    public static double averagePath(int m) {
        if (m <= 1) return 0.0;
        if (m == 2) return 1.0;
        return 2.0 * (Math.log(m - 1) + EULER) - 2.0 * (m - 1) / m;
    }

    /** The sample size each tree was built over: {@code min(sampleSize, n)}. */
    public int sampleSize() {
        return psi;
    }

    public int trees() {
        return trees.size();
    }
}
