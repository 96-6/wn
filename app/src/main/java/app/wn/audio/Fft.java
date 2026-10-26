package app.wn.audio;

/** In-place radix-2 complex FFT; no allocations in the audio callback. */
final class Fft {
    static void transform(double[] re, double[] im, boolean inverse) {
        int n = re.length;
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) j ^= bit;
            j ^= bit;
            if (i < j) {
                double t = re[i]; re[i] = re[j]; re[j] = t;
                t = im[i]; im[i] = im[j]; im[j] = t;
            }
        }
        for (int size = 2; size <= n; size <<= 1) {
            double angle = (inverse ? 2 : -2) * Math.PI / size;
            double wrStep = Math.cos(angle), wiStep = Math.sin(angle);
            for (int start = 0; start < n; start += size) {
                double wr = 1, wi = 0;
                for (int j = 0; j < size / 2; j++) {
                    int a = start + j, b = a + size / 2;
                    double br = re[b] * wr - im[b] * wi, bi = re[b] * wi + im[b] * wr;
                    re[b] = re[a] - br; im[b] = im[a] - bi;
                    re[a] += br; im[a] += bi;
                    double nextWr = wr * wrStep - wi * wiStep;
                    wi = wr * wiStep + wi * wrStep; wr = nextWr;
                }
            }
        }
        if (inverse) for (int i = 0; i < n; i++) { re[i] /= n; im[i] /= n; }
    }
}
