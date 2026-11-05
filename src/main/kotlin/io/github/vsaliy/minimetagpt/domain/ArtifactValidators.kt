package io.github.vsaliy.minimetagpt.domain

/** Pure validators for every contract exchanged between agents. Nothing is passed on without passing these. */
object ArtifactValidators {
    const val MAX_FILE_BYTES = 200_000
    const val MAX_FILES = 200
    const val MAX_TOTAL_BYTES = 2_000_000

    private val projectName = Regex("[a-z][a-z0-9-]{1,63}")
    private val javaPackage = Regex("[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+")
    private val httpMethods = setOf("GET", "POST", "PUT", "PATCH", "DELETE")

    /** Returns an error message when [path] is not a safe, relative, normalized workspace path. */
    fun pathProblem(path: String): String? = when {
        path.isBlank() -> "path is blank"
        path.length > 255 -> "path is too long"
        path.contains('\u0000') || path.contains('\\') -> "path contains forbidden characters"
        path.startsWith("/") || Regex("^[A-Za-z]:").containsMatchIn(path) -> "path must be relative"
        path.split('/').any { it == ".." || it == "." || it.isEmpty() } -> "path must be normalized and must not contain '..'"
        path.split('/').first() == ".git" -> "path must not target .git"
        else -> null
    }

    fun requirements(content: RequirementsContent): ValidationResult = collect {
        require(content.title.isNotBlank(), "title", "must not be blank")
        require(content.summary.isNotBlank(), "summary", "must not be blank")
        require(content.functionalRequirements.isNotEmpty(), "functionalRequirements", "at least one required")
        require(content.acceptanceCriteria.isNotEmpty(), "acceptanceCriteria", "at least one required")
        val all = content.functionalRequirements + content.nonFunctionalRequirements
        all.forEachIndexed { i, r ->
            require(r.id.isNotBlank(), "requirements[$i].id", "must not be blank")
            require(r.description.isNotBlank(), "requirements[$i].description", "must not be blank")
        }
        val ids = all.map { it.id }
        require(ids.size == ids.toSet().size, "requirements", "ids must be unique")
        val criterionIds = content.acceptanceCriteria.map { it.id }
        require(criterionIds.size == criterionIds.toSet().size, "acceptanceCriteria", "ids must be unique")
        content.acceptanceCriteria.forEachIndexed { i, c ->
            require(c.description.isNotBlank(), "acceptanceCriteria[$i].description", "must not be blank")
            require(c.requirementId in ids, "acceptanceCriteria[$i].requirementId", "unknown requirement '${c.requirementId}'")
        }
        if (content.openQuestions.isNotEmpty() && content.assumptions.isEmpty()) {
            warn("assumptions", "open questions exist but no assumptions were recorded")
        }
    }

    fun architecture(content: ArchitectureContent): ValidationResult = collect {
        require(projectName.matches(content.projectName), "projectName", "must match ${projectName.pattern}")
        require(javaPackage.matches(content.basePackage), "basePackage", "must be a valid lowercase Java package")
        require(content.components.isNotEmpty(), "components", "at least one required")
        require(content.endpoints.isNotEmpty(), "endpoints", "at least one required")
        require(content.persistence.strategy.isNotBlank(), "persistence.strategy", "must not be blank")
        content.endpoints.forEachIndexed { i, e ->
            require(e.method.uppercase() in httpMethods, "endpoints[$i].method", "unsupported method '${e.method}'")
            require(e.path.startsWith("/"), "endpoints[$i].path", "must start with '/'")
            require(e.successStatus in 200..299, "endpoints[$i].successStatus", "must be 2xx")
        }
        val known = (content.entities + content.dtos).map { it.name }.toSet()
        content.endpoints.forEachIndexed { i, e ->
            listOfNotNull(e.requestType, e.responseType).filter { it.isNotBlank() }.forEach { t ->
                val base = t.removePrefix("List<").removeSuffix(">")
                if (base !in known) warn("endpoints[$i]", "type '$base' is not declared as an entity or DTO")
            }
        }
        content.dependencies.forEachIndexed { i, d ->
            require(d.groupId.isNotBlank() && d.artifactId.isNotBlank(), "dependencies[$i]", "groupId and artifactId are required")
        }
    }

    fun implementation(draft: ImplementationDraft, existing: Set<String> = emptySet()): ValidationResult = collect {
        files(draft.files, "files")
        val paths = draft.files.map { it.path }.toSet() + existing
        if (existing.isEmpty()) {
            require("pom.xml" in paths, "files", "pom.xml is required")
            require(paths.any { it.startsWith("src/main/java/") && it.endsWith(".java") }, "files", "at least one Java source is required")
        }
        draft.files.forEachIndexed { i, f ->
            require(!f.path.startsWith("src/test/"), "files[$i].path", "tests are owned by the QA agent")
        }
    }

    fun tests(draft: TestSuiteDraft, requirements: RequirementsContent): ValidationResult = collect {
        files(draft.testFiles, "testFiles")
        require(draft.testFiles.isNotEmpty(), "testFiles", "at least one test file required")
        draft.testFiles.forEachIndexed { i, f ->
            require(f.path.startsWith("src/test/java/") && f.path.endsWith(".java"), "testFiles[$i].path", "must be a Java file under src/test/java")
        }
        val criterionIds = requirements.acceptanceCriteria.map { it.id }.toSet()
        draft.acceptanceCoverage.forEachIndexed { i, c ->
            require(c.criterionId in criterionIds, "acceptanceCoverage[$i].criterionId", "unknown criterion '${c.criterionId}'")
        }
        (criterionIds - draft.acceptanceCoverage.map { it.criterionId }.toSet()).forEach {
            warn("acceptanceCoverage", "criterion $it has no mapped test")
        }
    }

    private fun ValidationCollector.files(files: List<GeneratedFile>, root: String) {
        require(files.size <= MAX_FILES, root, "too many files (max $MAX_FILES)")
        require(files.map { it.path }.let { it.size == it.toSet().size }, root, "duplicate paths")
        var total = 0L
        files.forEachIndexed { i, f ->
            pathProblem(f.path)?.let { error("$root[$i].path", it) }
            val size = f.content.toByteArray().size
            total += size
            require(size <= MAX_FILE_BYTES, "$root[$i].content", "file exceeds $MAX_FILE_BYTES bytes")
        }
        require(total <= MAX_TOTAL_BYTES, root, "total size exceeds $MAX_TOTAL_BYTES bytes")
    }

    private inline fun collect(block: ValidationCollector.() -> Unit): ValidationResult =
        ValidationCollector().apply(block).result()
}
