import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.TaskAction

abstract class VerifyCoreQueryClasspath : DefaultTask() {
    @get:Classpath
    abstract val coreClasspath: ConfigurableFileCollection

    @TaskAction
    fun verify() {
        val queryArtifacts =
            coreClasspath.files.filter { file ->
                file.name.startsWith("query-") ||
                    file.name.startsWith("kotlin-query-") ||
                    file.invariantSeparatorsPath.contains("/surrealdb-kotlin-query/build/")
            }
        check(queryArtifacts.isEmpty()) {
            "core compile classpath contains query artifacts: ${queryArtifacts.joinToString()}"
        }
    }
}
