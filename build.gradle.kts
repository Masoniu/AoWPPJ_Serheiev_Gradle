plugins {
    java
}

group = "com.example"
version = "1.0.0"

apply<ProjectAnalysis>()

tasks.register<Copy>("backupSources") {
    group = "custom tasks"
    description = "Makes backup copy of the project"
    from("src/main/java")
    into(layout.buildDirectory.dir("backup-sources"))
    doFirst {
        println("Starting files backup...")
    }

    doLast {
        println("Backup created successfully in build/backup-sources")
    }
}

tasks.named("backupSources") {
    dependsOn("projectStats")
}