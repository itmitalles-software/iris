package io.wutz.apollo.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The licenses screen shows copies of the repository's license files (res/raw); they must not
 * drift, and every library the app declares must be covered by the third-party list.
 */
class OpenSourceLicensesTest {
    @Test
    fun `bundled copies match the repository license files`() {
        mapOf(
            "LICENSE" to "license_apache_2_0.txt",
            "NOTICE" to "notice.txt",
            "docs/licenses/Cinzel-OFL.txt" to "license_cinzel_ofl.txt",
        ).forEach { (source, copy) ->
            assertEquals(
                "res/raw/$copy is out of date: copy $source again",
                repoFile(source).readText(),
                repoFile("app/src/main/res/raw/$copy").readText(),
            )
        }
    }

    @Test
    fun `every declared library group is covered by the third-party list`() {
        val catalog = repoFile("gradle/libs.versions.toml").readText()
        val build = repoFile("app/build.gradle.kts").readText()
        val aliasToGroup =
            Regex(
                """^([a-z0-9-]+)\s*=\s*\{\s*(?:module\s*=\s*"([^":]+):|group\s*=\s*"([^"]+)")""",
                RegexOption.MULTILINE,
            ).findAll(catalog)
                .associate { it.groupValues[1].replace('-', '.') to (it.groupValues[2].ifEmpty { it.groupValues[3] }) }
        val runtimeGroups =
            Regex("""^\s*implementation\((libs\.[a-zA-Z0-9.]+|"([^":]+):[^"]+")\)""", RegexOption.MULTILINE)
                .findAll(build)
                .mapNotNull { match ->
                    match.groupValues[2].ifEmpty { null }
                        ?: aliasToGroup[match.groupValues[1].removePrefix("libs.")]
                }.toSet()
        assertTrue("no runtime dependencies found", runtimeGroups.size > 5)

        val listed = repoFile("app/src/main/res/raw/third_party_licenses.txt").readText()
        val patterns =
            Regex("""^(?:Maven group: )?([a-z0-9-]+(?:\.[a-z0-9-]+)*(?:\.\*)?)$""", RegexOption.MULTILINE)
                .findAll(listed)
                .map { it.groupValues[1] }
                .toList()
        val uncovered =
            runtimeGroups.filterNot { group ->
                patterns.any { pattern ->
                    if (pattern.endsWith(".*")) group.startsWith(pattern.removeSuffix("*")) else group == pattern
                }
            }
        assertEquals("add these groups to res/raw/third_party_licenses.txt", emptyList<String>(), uncovered)
    }

    private fun repoFile(path: String): File = listOf(File("../$path"), File(path)).first { it.exists() }
}
