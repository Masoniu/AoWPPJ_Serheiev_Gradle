import org.gradle.api.Plugin
import org.gradle.api.Project
import java.security.MessageDigest

class SearchEngineValidation : Plugin<Project> {
    override fun apply(project: Project) {

        project.tasks.register("verifyIndexEncoding") {
            group = "engine operations"
            description = "Checks binary header for Java Serialization suitability in .dat indexes"

            doLast {
                val datFiles = project.fileTree(project.projectDir) { include("*.dat") }
                val javaMagic = byteArrayOf(0xAC.toByte(), 0xED.toByte(), 0x00.toByte(), 0x05.toByte())

                if (datFiles.isEmpty) {
                    println("Index files (.dat) not found.")
                    return@doLast
                }

                println("Verifying binary structure of indexes...")
                datFiles.forEach { file ->
                    val header = file.inputStream().use { it.readNBytes(4) }

                    if (header.contentEquals(javaMagic)) {
                        println("[Valid Java Object] ${file.name} - correct encoding")
                    } else {
                        val hexHeader = header.joinToString(" ") { "%02X".format(it) }
                        throw RuntimeException("ENCODING ERROR: ${file.name} has header [$hexHeader], expected [AC ED 00 05]!")
                    }
                }
            }
        }

        project.tasks.register("generateIndexManifest") {
            group = "engine operations"
            description = "Computes SHA-256 control sums for the indexes"

            doLast {
                val indexFiles = project.fileTree(project.projectDir) { include("*.dat", "*.bin") }
                val buildDir = project.layout.buildDirectory.get().asFile
                buildDir.mkdirs()
                val manifestFile = buildDir.resolve("index_manifest.json")

                val sb = java.lang.StringBuilder("{\n  \"engine_version\": \"${project.version}\",\n  \"indexes\": [\n")

                indexFiles.forEachIndexed { index, file ->
                    val sizeMb = file.length() / (1024L * 1024L)
                    println("Computing SHA-256 for ${file.name} (~$sizeMb MB)...")

                    val md = MessageDigest.getInstance("SHA-256")

                    file.inputStream().use { fis ->
                        val buffer = ByteArray(8192)
                        var bytesRead: Int
                        while (fis.read(buffer).also { bytesRead = it } != -1) {
                            md.update(buffer, 0, bytesRead)
                        }
                    }

                    val hashBytes = md.digest()
                    val hashHex = hashBytes.joinToString("") { "%02x".format(it) }

                    sb.append("    {\n")
                    sb.append("      \"file\": \"${file.name}\",\n")
                    sb.append("      \"size_bytes\": ${file.length()},\n")
                    sb.append("      \"sha256\": \"$hashHex\"\n")
                    sb.append("    }")
                    if (index < indexFiles.count() - 1) sb.append(",")
                    sb.append("\n")
                }
                sb.append("  ]\n}")

                manifestFile.writeText(sb.toString())
                println("Index security manifest generated: ${manifestFile.absolutePath}")
            }
        }
    }
}