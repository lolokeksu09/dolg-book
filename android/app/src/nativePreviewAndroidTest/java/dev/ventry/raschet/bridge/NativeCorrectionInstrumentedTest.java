package dev.ventry.raschet.bridge;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class NativeCorrectionInstrumentedTest {
    @Test public void packagedRustLibraryMatchesCorrectionContract() throws Exception {
        assertTrue(dev.ventry.raschet.BuildConfig.RUST_PREVIEW);
        NativeCorrection.loadAndroidLibrary();
        int count = 0;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                InstrumentationRegistry.getInstrumentation().getContext().getAssets().open("correction.tsv"),
                StandardCharsets.UTF_8))) {
            for (String line; (line = reader.readLine()) != null;) {
                if (line.isEmpty() || line.startsWith("#")) continue;
                String[] f = line.split(" ");
                assertEquals(line, 5, f.length);
                int kind = "debt".equals(f[1]) ? NativeCorrection.DEBT
                        : "payment".equals(f[1]) ? NativeCorrection.PAYMENT : 0;
                long expected;
                switch (f[4]) {
                    case "invalid": expected = NativeCorrection.INVALID; break;
                    case "paid": expected = NativeCorrection.PAID; break;
                    case "range": expected = NativeCorrection.RANGE; break;
                    default: expected = Long.parseLong(f[4]);
                }
                long available = Long.parseLong(f[0]);
                long oldAmount = Long.parseLong(f[2]);
                long replacement = Long.parseLong(f[3]);
                assertEquals(line, expected, NativeCorrection.correctedBalance(kind, available, oldAmount, replacement));
                if (expected >= 0) {
                    assertEquals(line, expected, CorrectionEngine.correctedBalance(available, f[1], oldAmount, replacement));
                } else {
                    try {
                        CorrectionEngine.correctedBalance(available, f[1], oldAmount, replacement);
                        fail("Invalid correction accepted: " + line);
                    } catch (IllegalArgumentException expectedFailure) { }
                }
                count++;
            }
        }
        assertEquals(18, count);
    }
}
