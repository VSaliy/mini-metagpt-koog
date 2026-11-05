package io.github.vsaliy.minimetagpt.domain

enum class AgentRole(val displayName: String) {
    REQUIREMENTS("Requirements Agent"),
    ARCHITECT("Software Architect Agent"),
    DEVELOPER("Developer Agent"),
    QA("QA Engineer Agent"),
}
