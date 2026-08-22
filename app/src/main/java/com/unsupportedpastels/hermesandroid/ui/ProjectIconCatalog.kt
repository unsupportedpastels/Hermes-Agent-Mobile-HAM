package com.unsupportedpastels.hermesandroid.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Factory
import androidx.compose.material.icons.outlined.FitnessCenter
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.HealthAndSafety
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LocalFireDepartment
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Payments
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.RocketLaunch
import androidx.compose.material.icons.outlined.School
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.ShoppingCart
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.ui.graphics.vector.ImageVector
import com.unsupportedpastels.hermesandroid.app.ProjectSummary

enum class ProjectIconId(val persistedValue: String) {
    Home("home"),
    Folder("folder"),
    Code("code"),
    Terminal("terminal"),
    Storage("storage"),
    Cloud("cloud"),
    Globe("globe"),
    Shield("shield"),
    Bug("bug"),
    Build("build"),
    Factory("factory"),
    Science("science"),
    Health("health"),
    Fitness("fitness"),
    Shopping("shopping"),
    Payments("payments"),
    Robot("robot"),
    Sparkles("sparkles"),
    Rocket("rocket"),
    School("school"),
    Game("game"),
    Music("music"),
    Camera("camera"),
    Fire("fire"),
    ;

    companion object {
        fun fromPersistedValue(value: String): ProjectIconId? =
            entries.firstOrNull { it.persistedValue == value }
    }
}

data class ProjectIconOption(
    val id: ProjectIconId,
    val label: String,
    val searchTerms: Set<String>,
)

object ProjectIconCatalog {
    val entries: List<ProjectIconOption> = listOf(
        ProjectIconOption(ProjectIconId.Home, "Home", setOf("house", "start")),
        ProjectIconOption(ProjectIconId.Folder, "Folder", setOf("project", "files")),
        ProjectIconOption(ProjectIconId.Code, "Code", setOf("development", "repository")),
        ProjectIconOption(ProjectIconId.Terminal, "Terminal", setOf("server", "command")),
        ProjectIconOption(ProjectIconId.Storage, "Storage", setOf("database", "data")),
        ProjectIconOption(ProjectIconId.Cloud, "Cloud", setOf("hosting", "infrastructure")),
        ProjectIconOption(ProjectIconId.Globe, "Globe", setOf("web", "network")),
        ProjectIconOption(ProjectIconId.Shield, "Shield", setOf("security", "protection")),
        ProjectIconOption(ProjectIconId.Bug, "Bug", setOf("debug", "testing")),
        ProjectIconOption(ProjectIconId.Build, "Build", setOf("tools", "engineering")),
        ProjectIconOption(ProjectIconId.Factory, "Factory", setOf("foundry", "manufacturing")),
        ProjectIconOption(ProjectIconId.Science, "Science", setOf("lab", "research")),
        ProjectIconOption(ProjectIconId.Health, "Health", setOf("medical", "care")),
        ProjectIconOption(ProjectIconId.Fitness, "Fitness", setOf("exercise", "training")),
        ProjectIconOption(ProjectIconId.Shopping, "Shopping", setOf("store", "commerce")),
        ProjectIconOption(ProjectIconId.Payments, "Payments", setOf("money", "finance")),
        ProjectIconOption(ProjectIconId.Robot, "Robot", setOf("agent", "automation", "ai")),
        ProjectIconOption(ProjectIconId.Sparkles, "Sparkles", setOf("creative", "magic")),
        ProjectIconOption(ProjectIconId.Rocket, "Rocket", setOf("launch", "deploy")),
        ProjectIconOption(ProjectIconId.School, "School", setOf("learning", "education")),
        ProjectIconOption(ProjectIconId.Game, "Game", setOf("gaming", "controller")),
        ProjectIconOption(ProjectIconId.Music, "Music", setOf("audio", "song")),
        ProjectIconOption(ProjectIconId.Camera, "Camera", setOf("photo", "image")),
        ProjectIconOption(ProjectIconId.Fire, "Fire", setOf("crawl", "hot")),
    )
}

fun projectIconVector(id: ProjectIconId): ImageVector = when (id) {
    ProjectIconId.Home -> Icons.Outlined.Home
    ProjectIconId.Folder -> Icons.Outlined.Folder
    ProjectIconId.Code -> Icons.Outlined.Code
    ProjectIconId.Terminal -> Icons.Outlined.Terminal
    ProjectIconId.Storage -> Icons.Outlined.Storage
    ProjectIconId.Cloud -> Icons.Outlined.Cloud
    ProjectIconId.Globe -> Icons.Outlined.Public
    ProjectIconId.Shield -> Icons.Outlined.Shield
    ProjectIconId.Bug -> Icons.Outlined.BugReport
    ProjectIconId.Build -> Icons.Outlined.Build
    ProjectIconId.Factory -> Icons.Outlined.Factory
    ProjectIconId.Science -> Icons.Outlined.Science
    ProjectIconId.Health -> Icons.Outlined.HealthAndSafety
    ProjectIconId.Fitness -> Icons.Outlined.FitnessCenter
    ProjectIconId.Shopping -> Icons.Outlined.ShoppingCart
    ProjectIconId.Payments -> Icons.Outlined.Payments
    ProjectIconId.Robot -> Icons.Outlined.SmartToy
    ProjectIconId.Sparkles -> Icons.Outlined.AutoAwesome
    ProjectIconId.Rocket -> Icons.Outlined.RocketLaunch
    ProjectIconId.School -> Icons.Outlined.School
    ProjectIconId.Game -> Icons.Outlined.SportsEsports
    ProjectIconId.Music -> Icons.Outlined.MusicNote
    ProjectIconId.Camera -> Icons.Outlined.PhotoCamera
    ProjectIconId.Fire -> Icons.Outlined.LocalFireDepartment
}

fun defaultProjectIconId(project: ProjectSummary): ProjectIconId = when (
    project.label.trim().lowercase()
) {
    "home" -> ProjectIconId.Home
    "overwatch" -> ProjectIconId.Shield
    "foundry" -> ProjectIconId.Factory
    "ham", "mercury" -> ProjectIconId.Terminal
    "firecrawl" -> ProjectIconId.Fire
    else -> ProjectIconId.Folder
}
