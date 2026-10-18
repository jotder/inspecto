package com.gamma.analytics.model;

import com.gamma.analytics.Stats;
import com.gamma.analytics.score.EntityCap;
import com.gamma.analytics.split.HoldoutSplit;
import com.gamma.graph.RunControl;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalDouble;

/**
 * Binary logistic regression (ANALYTICS-CORE-1 C3), hand-written: ridge-penalised Newton / IRLS on standardised inputs,
 * with the S5(d) deterministic hash holdout ({@link HoldoutSplit.HashSplit}) deciding which entities train, validate and
 * test. <b>Fitting is a Job / offline concern (W7)</b>: nothing on the request path calls {@link #fit}; the request path
 * only scores ({@link Model#probability}, {@link LogisticSql}) against a stored model version.
 *
 * <p>The contract:
 * <ul>
 *   <li><b>Deterministic with no randomness.</b> Newton's method has no start to draw (coefficients begin at 0) and the
 *       split is a hash of {@code seed:key}, so the same rows and {@link Spec} give the same {@link Model} bit for bit.
 *       The seed is the split's; rows are summed in the order given, so callers sort by entity key.</li>
 *   <li><b>No leakage.</b> The standardisation (mean, population standard deviation; a constant column has scale 1) is
 *       taken from the <i>train</i> rows only; validation and test rows are scored with it, never inside it.</li>
 *   <li><b>Penalty.</b> L2 {@code λ·Σβ²} on the standardised coefficients, the intercept excluded; {@code λ > 0}
 *       (default {@value #DEFAULT_L2}) keeps the fit finite on separable data and the Hessian invertible.</li>
 *   <li><b>Stop.</b> when the largest coefficient step is below {@value #TOLERANCE}, or after {@link Spec#maxIterations()}
 *       Newton steps (default {@value #DEFAULT_MAX_ITERATIONS}, at most {@value #MAX_ITERATIONS}); {@link Model#converged()}
 *       says which.</li>
 *   <li><b>Bounded input (D-AD10).</b> more than {@link Spec#maxRows()} rows fails ({@link InputCap.Exceeded}).</li>
 *   <li><b>Cancellation.</b> {@link RunControl#checkpoint()} once per Newton step.</li>
 *   <li><b>Recompute from explanation.</b> a score is {@code 100·σ(b0 + Σ bᵢ·zᵢ)}; {@link Model#contributions} returns the
 *       {@code bᵢ·zᵢ} terms, and {@link #recompute} rebuilds the score from them and the intercept alone.</li>
 * </ul>
 * Both classes must be present in the train rows; the validation and test metrics are reported for whichever rows land
 * there (an empty share has no metrics).
 */
public final class LogisticRegression {
    private LogisticRegression() {}

    public static final double DEFAULT_L2 = 1.0;
    public static final int DEFAULT_MAX_ITERATIONS = 25;
    public static final int MAX_ITERATIONS = 100;
    public static final double TOLERANCE = 1e-8;
    /** The most feature columns one fit takes. */
    public static final int MAX_FEATURES = 50;

    /** What to fit; {@code split} decides train / validation / test by entity key. */
    public record Spec(HoldoutSplit.HashSplit split, double l2, int maxIterations, int maxRows) {
        public Spec {
            if (split == null) throw new IllegalArgumentException("a holdout split is required");
            if (!(l2 > 0) || !Double.isFinite(l2)) throw new IllegalArgumentException("l2 must be a positive number: " + l2);
            if (maxIterations < 1 || maxIterations > MAX_ITERATIONS)
                throw new IllegalArgumentException("maxIterations must be within 1.." + MAX_ITERATIONS + ": " + maxIterations);
            InputCap.resolve(maxRows);
        }

        public static Spec of(HoldoutSplit.HashSplit split) {
            return new Spec(split, DEFAULT_L2, DEFAULT_MAX_ITERATIONS, EntityCap.DEFAULT);
        }
    }

    /** How a share of the rows is judged at the 0.5 cut: log-loss, AUC (empty when one class only), accuracy. */
    public record Metrics(long rows, long positives, double logLoss, OptionalDouble auc, double accuracy) {}

    /**
     * A fitted model: {@code intercept} and {@code coefficients} are on the standardised inputs
     * ({@code z = (x − mean) / scale}); the means and scales are the train rows'.
     */
    public record Model(double intercept, double[] coefficients, double[] means, double[] scales, int iterations,
                        boolean converged, Metrics train, Metrics validation, Metrics test) {

        public int features() {
            return coefficients.length;
        }

        private double[] z(double[] x) {
            if (x.length != features()) throw new IllegalArgumentException("a row has " + x.length + " values, the model " + features());
            double[] z = new double[x.length];
            for (int j = 0; j < z.length; j++) {
                if (Double.isNaN(x[j])) throw new IllegalArgumentException("a NaN value: drop or fill missing values first");
                z[j] = (x[j] - means[j]) / scales[j];
            }
            return z;
        }

        /** The per-input terms {@code bᵢ·zᵢ} of the log-odds, in input order. */
        public double[] contributions(double[] x) {
            double[] z = z(x), out = new double[z.length];
            for (int j = 0; j < z.length; j++) out[j] = coefficients[j] * z[j];
            return out;
        }

        /** {@code P(positive)} for a raw row. */
        public double probability(double[] x) {
            double s = intercept;
            for (double c : contributions(x)) s += c;
            return sigmoid(s);
        }

        /** The 0–100 score: {@code 100 · probability}. */
        public double score(double[] x) {
            return 100.0 * probability(x);
        }
    }

    /** {@code 100·σ(intercept + Σ contributions)} — the score rebuilt from its stored explanation alone. */
    public static double recompute(double intercept, double[] contributions) {
        double s = intercept;
        for (double c : contributions) s += c;
        return 100.0 * sigmoid(s);
    }

    /**
     * Fit on {@code x} (rows × features, finite) and {@code y} (true = positive class), the rows identified by
     * {@code keys} (the split's hash input).
     */
    public static Model fit(String[] keys, double[][] x, boolean[] y, Spec spec, RunControl control) {
        int n = x.length;
        if (n == 0) throw new IllegalArgumentException("logistic regression of no rows");
        if (keys.length != n || y.length != n) throw new IllegalArgumentException("keys, features and labels must have the same length");
        InputCap.require("logistic regression input", n, spec.maxRows());
        int d = x[0].length;
        if (d < 1 || d > MAX_FEATURES) throw new IllegalArgumentException("logistic regression takes 1.." + MAX_FEATURES + " features: " + d);
        for (double[] row : x) {
            if (row.length != d) throw new IllegalArgumentException("every row needs " + d + " values");
            for (double v : row)
                if (!Double.isFinite(v)) throw new IllegalArgumentException("a NaN or infinite value: drop or fill missing values first");
        }
        List<Integer> train = new ArrayList<>(), validation = new ArrayList<>(), test = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            switch (spec.split().role(keys[i])) {
                case TRAIN -> train.add(i);
                case VALIDATION -> validation.add(i);
                case TEST -> test.add(i);
            }
        }
        long pos = train.stream().filter(i -> y[i]).count();
        if (pos == 0 || pos == train.size())
            throw new IllegalArgumentException("the train rows need both classes: " + train.size() + " train rows, " + pos + " positive");

        // standardise by the train rows only
        int m = train.size();
        double[] means = new double[d], scales = new double[d];
        for (int j = 0; j < d; j++) {
            double s = 0;
            for (int i : train) s += x[i][j];
            means[j] = s / m;
            double ss = 0;
            for (int i : train) ss += (x[i][j] - means[j]) * (x[i][j] - means[j]);
            double sd = Math.sqrt(ss / m);
            scales[j] = sd > 0 ? sd : 1.0;
        }
        double[][] z = new double[m][d];
        double[] t = new double[m];
        for (int r = 0; r < m; r++) {
            int i = train.get(r);
            for (int j = 0; j < d; j++) z[r][j] = (x[i][j] - means[j]) / scales[j];
            t[r] = y[i] ? 1.0 : 0.0;
        }

        double[] beta = new double[d + 1];   // [intercept, b1..bd]
        int iterations = 0;
        boolean converged = false;
        while (iterations < spec.maxIterations()) {
            control.checkpoint();
            iterations++;
            double[] g = new double[d + 1];
            double[][] h = new double[d + 1][d + 1];
            for (int r = 0; r < m; r++) {
                double s = beta[0];
                for (int j = 0; j < d; j++) s += beta[j + 1] * z[r][j];
                double p = sigmoid(s), w = p * (1 - p), e = p - t[r];
                g[0] += e;
                h[0][0] += w;
                for (int j = 0; j < d; j++) {
                    g[j + 1] += e * z[r][j];
                    h[0][j + 1] += w * z[r][j];
                    for (int k = 0; k <= j; k++) h[k + 1][j + 1] += w * z[r][j] * z[r][k];
                }
            }
            for (int j = 0; j < d; j++) {
                g[j + 1] += spec.l2() * beta[j + 1];
                h[j + 1][j + 1] += spec.l2();
            }
            for (int a = 0; a <= d; a++)
                for (int b = a + 1; b <= d; b++) h[b][a] = h[a][b];   // mirror (only the upper triangle was summed)
            double[] step = solve(h, g);
            double largest = 0;
            for (int j = 0; j <= d; j++) {
                beta[j] -= step[j];
                largest = Math.max(largest, Math.abs(step[j]));
            }
            control.progress(iterations, spec.maxIterations());
            if (largest < TOLERANCE) {
                converged = true;
                break;
            }
        }
        double[] coefficients = new double[d];
        System.arraycopy(beta, 1, coefficients, 0, d);
        Model bare = new Model(beta[0], coefficients, means, scales, iterations, converged, null, null, null);
        return new Model(beta[0], coefficients, means, scales, iterations, converged, metrics(bare, train, x, y),
                metrics(bare, validation, x, y), metrics(bare, test, x, y));
    }

    private static Metrics metrics(Model model, List<Integer> rows, double[][] x, boolean[] y) {
        if (rows.isEmpty()) return null;
        double loss = 0;
        long correct = 0, positives = 0;
        double[] p = new double[rows.size()];
        for (int r = 0; r < p.length; r++) {
            int i = rows.get(r);
            p[r] = model.probability(x[i]);
            double clipped = Math.min(Math.max(p[r], 1e-15), 1 - 1e-15);
            loss -= y[i] ? Math.log(clipped) : Math.log(1 - clipped);
            if ((p[r] >= 0.5) == y[i]) correct++;
            if (y[i]) positives++;
        }
        OptionalDouble auc = OptionalDouble.empty();
        long negatives = rows.size() - positives;
        if (positives > 0 && negatives > 0) {
            // Mann-Whitney: the positives' rank sum over the average ranks of the probabilities (ties count a half)
            double[] ranks = Stats.ranks(p);
            double sum = 0;
            for (int r = 0; r < p.length; r++) if (y[rows.get(r)]) sum += ranks[r];
            auc = OptionalDouble.of((sum - positives * (positives + 1) / 2.0) / ((double) positives * negatives));
        }
        return new Metrics(rows.size(), positives, loss / rows.size(), auc, (double) correct / rows.size());
    }

    static double sigmoid(double s) {
        return s >= 0 ? 1.0 / (1.0 + Math.exp(-s)) : Math.exp(s) / (1.0 + Math.exp(s));
    }

    /** Solve {@code H·x = g} by Gaussian elimination with partial pivoting; refuses a singular system. */
    private static double[] solve(double[][] hIn, double[] gIn) {
        int n = gIn.length;
        double[][] a = new double[n][];
        for (int i = 0; i < n; i++) {
            a[i] = new double[n + 1];
            System.arraycopy(hIn[i], 0, a[i], 0, n);
            a[i][n] = gIn[i];
        }
        for (int c = 0; c < n; c++) {
            int piv = c;
            for (int r = c + 1; r < n; r++) if (Math.abs(a[r][c]) > Math.abs(a[piv][c])) piv = r;
            if (Math.abs(a[piv][c]) < 1e-12) throw new IllegalArgumentException("logistic regression: the inputs are linearly dependent");
            double[] tmp = a[c];
            a[c] = a[piv];
            a[piv] = tmp;
            for (int r = c + 1; r < n; r++) {
                double f = a[r][c] / a[c][c];
                for (int k = c; k <= n; k++) a[r][k] -= f * a[c][k];
            }
        }
        double[] x = new double[n];
        for (int r = n - 1; r >= 0; r--) {
            double s = a[r][n];
            for (int k = r + 1; k < n; k++) s -= a[r][k] * x[k];
            x[r] = s / a[r][r];
        }
        return x;
    }
}
