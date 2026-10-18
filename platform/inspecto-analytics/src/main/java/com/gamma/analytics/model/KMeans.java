package com.gamma.analytics.model;

import com.gamma.analytics.score.EntityCap;
import com.gamma.graph.RunControl;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * k-means clustering (ANALYTICS-CORE-1 C2): Lloyd's iterations from a k-means++ start, hand-written and
 * <b>deterministic</b> — the same points in the same order, the same {@link Spec} (seed included) always give the same
 * {@link Model}, to the last bit (sequential floating-point sums, a pinned {@link SeededRandom}, ties to the lowest
 * index). Callers must therefore hand the rows over in a stable order (sort by entity key).
 *
 * <p>The contract, each part stated so a stored model is reproducible and auditable:
 * <ul>
 *   <li><b>Standardised inputs.</b> Every column is z-scored by the fit's own mean and population standard deviation
 *       (a constant column has scale 1, so it contributes nothing); the {@link Model} keeps the means and scales so an
 *       entity is assigned in the same space ({@link Model#assign}, {@link KMeansSql}).</li>
 *   <li><b>Start.</b> k-means++: the first centre is a uniformly drawn point, each next one is drawn with probability
 *       proportional to its squared distance to the nearest centre already chosen (cumulative sum, one
 *       {@link SeededRandom#nextDouble()} per centre).</li>
 *   <li><b>Stop.</b> when no assignment changes, or after {@link Spec#maxIterations()} sweeps (default
 *       {@value #DEFAULT_MAX_ITERATIONS}, at most {@value #MAX_ITERATIONS}); {@link Model#converged()} says which. It does
 *       not stop on a tolerance.</li>
 *   <li><b>Empty cluster.</b> takes the point farthest from its own centre (lowest index on a tie) — deterministic.</li>
 *   <li><b>Labels.</b> clusters are numbered by their centroid, ascending lexicographically in standardised space, so
 *       cluster 0 is the same group however the start fell; naming clusters ("Champions") is the operator's step.</li>
 *   <li><b>Bounded input (D-AD10).</b> more than {@link Spec#maxPoints()} rows fails ({@link InputCap.Exceeded}); the
 *       input is meant to be pre-aggregated, one row per entity.</li>
 *   <li><b>Cancellation.</b> {@link RunControl#checkpoint()} once per sweep and per k-means++ centre.</li>
 * </ul>
 * Fitting is a Job / offline concern (W7): nothing here is for the request path except {@link Model#assign}.
 */
public final class KMeans {
    private KMeans() {}

    public static final int DEFAULT_MAX_ITERATIONS = 100;
    public static final int MAX_ITERATIONS = 1_000;
    /** The most clusters one fit takes (and the SQL assignment form's expression size). */
    public static final int MAX_K = 64;
    /** The most columns one fit takes. */
    public static final int MAX_DIMENSIONS = 50;

    /** What to fit: {@code k} clusters from a {@code seed}; caps as documented on {@link KMeans}. */
    public record Spec(int k, long seed, int maxIterations, int maxPoints) {
        public Spec {
            if (k < 2 || k > MAX_K) throw new IllegalArgumentException("k must be within 2.." + MAX_K + ": " + k);
            if (maxIterations < 1 || maxIterations > MAX_ITERATIONS)
                throw new IllegalArgumentException("maxIterations must be within 1.." + MAX_ITERATIONS + ": " + maxIterations);
            InputCap.resolve(maxPoints);
        }

        public static Spec of(int k, long seed) {
            return new Spec(k, seed, DEFAULT_MAX_ITERATIONS, EntityCap.DEFAULT);
        }
    }

    /**
     * A fitted model — everything needed to assign a new entity and to audit the fit. {@code centroids} are in
     * standardised space ({@code (x − mean) / scale}); {@code sizes} count the training points per cluster.
     */
    public record Model(int k, long seed, double[] means, double[] scales, double[][] centroids, long[] sizes,
                        double inertia, int iterations, boolean converged) {

        public int dimensions() {
            return means.length;
        }

        /** The nearest cluster of a raw point (lowest index on a tie): standardise, then nearest centre. */
        public int assign(double[] point) {
            if (point.length != dimensions())
                throw new IllegalArgumentException("a point has " + point.length + " values, the model " + dimensions());
            double[] z = new double[point.length];
            for (int j = 0; j < z.length; j++) {
                if (Double.isNaN(point[j])) throw new IllegalArgumentException("a NaN value: drop or fill missing values first");
                z[j] = (point[j] - means[j]) / scales[j];
            }
            return nearest(z, centroids);
        }
    }

    /** A fit's model and each training point's cluster (same order as the input). */
    public record Fit(Model model, int[] labels) {}

    /** Fit {@code points} (rows × columns, finite) — see the class contract. */
    public static Fit fit(double[][] points, Spec spec, RunControl control) {
        int n = points.length;
        if (n == 0) throw new IllegalArgumentException("k-means of no points");
        InputCap.require("k-means input", n, spec.maxPoints());
        if (n < spec.k()) throw new IllegalArgumentException("k-means needs at least k points: " + n + " points, k = " + spec.k());
        int d = points[0].length;
        if (d < 1 || d > MAX_DIMENSIONS) throw new IllegalArgumentException("k-means takes 1.." + MAX_DIMENSIONS + " columns: " + d);
        for (double[] p : points) {
            if (p.length != d) throw new IllegalArgumentException("every point needs " + d + " values");
            for (double v : p)
                if (!Double.isFinite(v)) throw new IllegalArgumentException("a NaN or infinite value: drop or fill missing values first");
        }
        double[] means = new double[d], scales = new double[d];
        for (int j = 0; j < d; j++) {
            double s = 0;
            for (double[] p : points) s += p[j];
            means[j] = s / n;
            double ss = 0;
            for (double[] p : points) ss += (p[j] - means[j]) * (p[j] - means[j]);
            double sd = Math.sqrt(ss / n);
            scales[j] = sd > 0 ? sd : 1.0;
        }
        double[][] z = new double[n][d];
        for (int i = 0; i < n; i++)
            for (int j = 0; j < d; j++) z[i][j] = (points[i][j] - means[j]) / scales[j];

        int k = spec.k();
        double[][] centroids = plusPlus(z, k, new SeededRandom(spec.seed()), control);
        int[] label = new int[n];
        Arrays.fill(label, -1);
        int iterations = 0;
        boolean converged = false;
        double[] nearestDist = new double[n];
        while (iterations < spec.maxIterations()) {
            control.checkpoint();
            iterations++;
            boolean changed = false;
            for (int i = 0; i < n; i++) {
                int c = nearest(z[i], centroids);
                nearestDist[i] = sqDist(z[i], centroids[c]);
                if (c != label[i]) {
                    label[i] = c;
                    changed = true;
                }
            }
            if (!changed) {
                converged = true;
                break;
            }
            centroids = update(z, label, centroids, nearestDist);
            control.progress(iterations, spec.maxIterations());
        }
        // order the clusters by centroid, then relabel the points
        Integer[] order = new Integer[k];
        for (int c = 0; c < k; c++) order[c] = c;
        final double[][] cs = centroids;
        Arrays.sort(order, Comparator.comparing((Integer c) -> cs[c], KMeans::lexicographic));
        int[] newIndex = new int[k];
        double[][] sorted = new double[k][];
        for (int r = 0; r < k; r++) {
            newIndex[order[r]] = r;
            sorted[r] = centroids[order[r]];
        }
        long[] sizes = new long[k];
        double inertia = 0;
        for (int i = 0; i < n; i++) {
            label[i] = newIndex[label[i]];
            sizes[label[i]]++;
            inertia += sqDist(z[i], sorted[label[i]]);
        }
        return new Fit(new Model(k, spec.seed(), means, scales, sorted, sizes, inertia, iterations, converged), label);
    }

    private static double[][] plusPlus(double[][] z, int k, SeededRandom rnd, RunControl control) {
        int n = z.length;
        List<double[]> chosen = new ArrayList<>(k);
        chosen.add(z[rnd.nextInt(n)].clone());
        double[] d2 = new double[n];
        Arrays.fill(d2, Double.POSITIVE_INFINITY);
        while (chosen.size() < k) {
            control.checkpoint();
            double[] last = chosen.get(chosen.size() - 1);
            double total = 0;
            for (int i = 0; i < n; i++) {
                d2[i] = Math.min(d2[i], sqDist(z[i], last));
                total += d2[i];
            }
            int pick;
            if (total <= 0) {
                pick = rnd.nextInt(n);   // every point sits on a centre already (duplicates): any point will do
            } else {
                double target = rnd.nextDouble() * total, run = 0;
                pick = n - 1;
                for (int i = 0; i < n; i++) {
                    run += d2[i];
                    if (run > target) { pick = i; break; }
                }
            }
            chosen.add(z[pick].clone());
        }
        return chosen.toArray(new double[0][]);
    }

    private static double[][] update(double[][] z, int[] label, double[][] old, double[] nearestDist) {
        int k = old.length, d = old[0].length;
        double[][] sum = new double[k][d];
        long[] count = new long[k];
        for (int i = 0; i < z.length; i++) {
            count[label[i]]++;
            for (int j = 0; j < d; j++) sum[label[i]][j] += z[i][j];
        }
        double[][] next = new double[k][];
        boolean[] taken = new boolean[z.length];
        for (int c = 0; c < k; c++) {
            if (count[c] > 0) {
                next[c] = new double[d];
                for (int j = 0; j < d; j++) next[c][j] = sum[c][j] / count[c];
            } else {
                // empty: the point farthest from its own centre (not already used), lowest index on a tie
                int far = -1;
                for (int i = 0; i < z.length; i++)
                    if (!taken[i] && count[label[i]] > 1 && (far < 0 || nearestDist[i] > nearestDist[far])) far = i;
                if (far < 0) far = 0;
                taken[far] = true;
                next[c] = z[far].clone();
            }
        }
        return next;
    }

    /** The index of the centre nearest {@code z} by squared distance, lowest index on a tie. */
    static int nearest(double[] z, double[][] centroids) {
        int best = 0;
        double bd = Double.POSITIVE_INFINITY;
        for (int c = 0; c < centroids.length; c++) {
            double dd = sqDist(z, centroids[c]);
            if (dd < bd) {
                bd = dd;
                best = c;
            }
        }
        return best;
    }

    private static double sqDist(double[] a, double[] b) {
        double s = 0;
        for (int j = 0; j < a.length; j++) {
            double t = a[j] - b[j];
            s += t * t;
        }
        return s;
    }

    private static int lexicographic(double[] a, double[] b) {
        for (int j = 0; j < a.length; j++) {
            int c = Double.compare(a[j], b[j]);
            if (c != 0) return c;
        }
        return 0;
    }
}
