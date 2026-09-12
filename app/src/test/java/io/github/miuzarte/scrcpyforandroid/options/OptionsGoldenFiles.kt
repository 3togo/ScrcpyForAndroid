package io.github.miuzarte.scrcpyforandroid.options

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.fail

/**
 * Text-file golden store for the scrcpy option pipeline.
 *
 * The pipeline `ScrcpyOptions.Bundle -> ClientOptions -> ServerParams -> command line` is the
 * single most behavior-sensitive pure logic in the app: a reordered or dropped argument silently
 * breaks streaming on device. These goldens freeze the exact argument list so the pipeline can be
 * restructured without changing behavior.
 *
 * Goldens live in `app/src/test/resources/options/<case>.txt`, one argument per line. Regenerate
 * them deliberately (never to make a failing assertion pass) with:
 *
 * ```
 * SCRCASTER_UPDATE_GOLDEN=1 ./gradlew :app:testDebugUnitTest --tests '*ClientOptionsPipelineTest*'
 * ```
 */
internal object OptionsGoldenFiles {
    private val updateRequested: Boolean
        get() = System.getProperty("scrcaster.golden.update").toBoolean() ||
            System.getenv("SCRCASTER_UPDATE_GOLDEN") == "1"

    /** Locate the golden dir whether the working directory is the app module or the repo root. */
    private fun goldenDir(): File {
        val candidates = listOf(
            File("src/test/resources/options"),
            File("app/src/test/resources/options"),
        )
        return candidates.firstOrNull { it.isDirectory }
            ?: candidates.first().apply { mkdirs() }
    }

    fun assertMatches(case: String, actual: List<String>) {
        val file = File(goldenDir(), "$case.txt")
        if (updateRequested) {
            file.parentFile?.mkdirs()
            file.writeText(actual.joinToString("\n") + "\n")
            return
        }
        if (!file.isFile) {
            fail(
                "Missing golden ${file.path} for case '$case'. Actual args:\n" +
                    actual.joinToString("\n"),
            )
        }
        val expected = file.readLines().filter { it.isNotEmpty() }
        assertEquals(
            "Argument list for case '$case' diverged from ${file.path} " +
                "(regenerate with SCRCASTER_UPDATE_GOLDEN=1 only for intentional changes)",
            expected,
            actual,
        )
    }
}
