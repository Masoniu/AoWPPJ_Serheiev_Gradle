import org.gradle.api.Plugin
import org.gradle.api.Project

class ProjectAnalysis : Plugin<Project>{
    override fun apply(project: Project) {
        project.tasks.register("projectStats") {
            group = "analytics"
            description = "Рахує кількість Java файлів у проєкті"

            doLast {
                val srcDir = project.file("src/main/java")
                if (srcDir.exists()) {
                    val javaFilesCount = srcDir.walkTopDown().count { it.isFile && it.name.endsWith(".java") }
                    println("Statistics of project '${project.name}':")
                    println("   Java files found: $javaFilesCount")
                } else {
                    println("Directory src/main/java was not found. Project may be empty")
                }
            }
        }
        project.tasks.register("buildEnvReport") {
            group = "analytics"
            description = "Displays info about JVM and system"

            doLast {
                println("Build environment:")
                println("   OS: ${System.getProperty("os.name")} (${System.getProperty("os.arch")})")
                println("   Java Version: ${System.getProperty("java.version")}")
                println("   Gradle Version: ${project.gradle.gradleVersion}")
            }
        }
    }
}