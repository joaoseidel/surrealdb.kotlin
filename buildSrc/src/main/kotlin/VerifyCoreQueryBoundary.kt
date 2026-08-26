import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.TaskAction

abstract class VerifyCoreQueryBoundary : DefaultTask() {
    @get:InputFile
    abstract val coreBuild: RegularFileProperty

    @get:InputFiles
    abstract val coreSources: ConfigurableFileCollection

    @TaskAction
    fun verify() {
        val dependency =
            Regex("""project\s*\(\s*[\"']?:surrealdb-kotlin-query[\"']?\s*\)""")
        check(!dependency.containsMatchIn(coreBuild.get().asFile.readText())) {
            "core -> query project dependency found in ${coreBuild.get().asFile}"
        }

        val queryImport =
            Regex("""^import (com\.surrealdb\.kotlin\.query(?:\.[A-Za-z0-9_*]+)?)""")
        val forbidden =
            coreSources.files
                .sorted()
                .flatMap { source ->
                    source.readLines().mapNotNull { line ->
                        queryImport
                            .find(line)
                            ?.groupValues
                            ?.get(1)
                            ?.let { "$source -> $it" }
                    }
                }

        check(forbidden.isEmpty()) {
            "core imports query-owned API:\n${forbidden.joinToString("\n") { "  $it" }}"
        }
    }
}
